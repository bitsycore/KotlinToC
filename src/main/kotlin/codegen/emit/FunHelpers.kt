package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.codegen.expression.genStrTemplateToSb
import com.bitsycore.ktc.codegen.expression.toStringMaxLen
import com.bitsycore.ktc.types.KtcType

// Shared helpers for function/method emit - return-type analysis, scope registration,
// and class method section grouping.

/* Method names inherited from Any. Each can be explicitly overridden by a class
   or object and is auto-generated when missing. */
internal val kAnyMethodNames: Set<String> = setOf("dispose", "toString", "hashCode")

/**
 * Computes all return-type fnCtx fields from [f] and returns the C return type string.
 *
 * Must be called **after** [saveFunState] so the previous function's state is preserved.
 * Pass [inferredType] as a fallback when [f] has no explicit return type and the body
 * can be block-inferred (free functions and class methods).
 */
internal fun CCodeGen.computeReturnInfo(f: FunDecl, inferredType: String? = null): String {
	val returnsNullable    = f.returnType != null && f.returnType.nullable
	val returnsSizedArray  = !returnsNullable && f.returnType != null && f.returnType.isSizedArray()
	val returnsSizedString = !returnsNullable && f.returnType != null && f.returnType.isSizedString()
	val retKtc             = f.returnType?.let { resolveTypeName(it) }
	val returnsArray       = !returnsNullable && !returnsSizedArray && (retKtc?.isArrayLike ?: false)
	val retResolved        = retKtc?.toInternalStr ?: inferredType ?: ""
	val optRetCType        = if (returnsNullable) optCTypeName(retResolved) else ""

	currentFnReturnsNullable    = returnsNullable
	currentFnReturnsArray       = returnsArray
	currentFnReturnsSizedArray  = returnsSizedArray
	currentFnReturnsSizedString = returnsSizedString
	currentFnOptReturnCTypeName = optRetCType
	currentFnReturnType         = retResolved
	currentFnReturnKtcType      = retKtc
	if (returnsSizedArray) {
		currentFnSizedArraySize     = f.returnType!!.getSizeAnnotation()!!
		currentFnSizedArrayElemType = retKtc!!.asArr!!.elem
		}
	if (returnsSizedString) {
		currentFnSizedStringSize = f.returnType!!.getSizeAnnotation()!!
		}

	return when {
		returnsSizedArray                        -> sizedArrayCTypeName(cTypeStr(retKtc!!.asArr!!.elem), f.returnType!!.getSizeAnnotation()!!)
		returnsSizedString                       -> sizedStringCTypeName(f.returnType!!.getSizeAnnotation()!!)
		returnsNullable && retKtc is KtcType.Any -> "ktc_Any"
		returnsNullable                          -> optRetCType
		returnsArray                             -> {
			val arrElem = retKtc!!.asArr?.elem ?: ((retKtc as? KtcType.Ptr)?.inner as KtcType.Arr).elem
			varArrTypeName(cTypeStr(arrElem))
			}
		retResolved.isNotEmpty()                 -> cTypeStr(retResolved)
		else                                     -> "void"
		}
	}

/**
 * Registers all function parameters in the current scope.
 * Handles vararg, nullable, and optional marking.
 */
internal fun CCodeGen.registerParams(params: List<Param>) {
	for (p in params) {
		val ktc = resolveTypeName(p.type)
		val str = ktc.toInternalStr
		val isNullable = p.type.isEffectivelyNullable()
		val finalKtc = parseResolvedTypeName(when {
			p.isVararg   -> "${str}Array"
			isNullable   -> "${str}?"
			else         -> str
			})
		defineVar(p.name, LocalVar(ktc = finalKtc, isParam = true))
		if (isNullable && isValueNullableKtc(KtcType.Nullable(ktc))) markOptional(p.name)
		}
	}

/**
 * Registers all class fields from [ci] in the current scope as [LocalVar] descriptors.
 * Computed (getter-only) properties have no backing field, so they are skipped here -
 * registering them with a phony `$self->name` cName would both produce spurious shadow
 * warnings on params of the same name and bypass getter inlining at access sites.
 * [selfPrefix] is the C access prefix - use `"\$self->"` for pointer self, `"\$self."` for value self.
 */
internal fun CCodeGen.registerClassFields(ci: ClassInfo, selfPrefix: String) {
	for (prop in ci.properties) {
		if (prop.getter != null) continue                            // computed property - no backing field
		val name = prop.name
		val type = prop.typeRef
		val ktc        = resolveTypeName(type)
		if (scopes.last().containsKey(name)) continue  // don't shadow params
		val cFieldName = if (name in ci.privateProps) "PRIV_$name" else name
		val isOpt      = type.nullable && !type.isRefType() && !ktc.isArrayLike
		defineVar(name, LocalVar(ktc = ktc, mutable = !ci.isValProp(name), optional = isOpt, cName = "$selfPrefix$cFieldName"))
		}
	}

/**
 * Groups non-Any members by their implementing interface.
 * Returns (ordered interface display strings, methods keyed by iface string or "" for unaffiliated).
 */
private fun CCodeGen.groupMethodsByIface(
	superInterfaces: List<TypeRef>,
	members: List<Decl>,
	anyMethodNames: Set<String>
	): Pair<List<String>, Map<String, List<FunDecl>>> {
	val ifaceMethodToStr = mutableMapOf<String, String>()
	val ifaceOrder       = mutableListOf<String>()
	fun collect(ifaceRef: TypeRef) {
		val ifaceName = resolveIfaceName(ifaceRef)
		val iface     = interfaces[ifaceName] ?: return
		val ifaceStr  = typeRefToStr(ifaceRef)
		if (ifaceStr !in ifaceOrder) ifaceOrder += ifaceStr
		for (m in iface.methods)   if (m.name !in ifaceMethodToStr) ifaceMethodToStr[m.name] = ifaceStr
		for (p in iface.propDecls) if (p.name !in ifaceMethodToStr) ifaceMethodToStr[p.name] = ifaceStr
		for (superRef in iface.superInterfaces) collect(superRef)
		}
	for (ifaceRef in superInterfaces) collect(ifaceRef)

	val methodsByIface = linkedMapOf<String, MutableList<FunDecl>>()
	for (m in members) {
		if (m is FunDecl && m.receiver == null && m.name !in anyMethodNames)
			methodsByIface.getOrPut(ifaceMethodToStr[m.name] ?: "") { mutableListOf() } += m
		}
	return Pair(ifaceOrder, methodsByIface)
	}

/**
 * Sets up class scope and emits non-Any method sections (per-interface + free methods).
 * Manages [currentClass], [selfIsPointer], [pushScope]/[popScope].
 * Returns true if any method section was emitted (caller uses this to place blank lines).
 */
internal fun CCodeGen.emitClassNonAnyMethods(
	className: String,
	superInterfaces: List<TypeRef>,
	members: List<Decl>,
	ci: ClassInfo
	): Boolean {
	val anyMethodNames = kAnyMethodNames
	val (ifaceOrder, methodsByIface) = groupMethodsByIface(superInterfaces, members, anyMethodNames)

	currentClass  = className
	selfIsPointer = true
	pushScope()
	registerClassFields(ci, "\$self->")

	var hasMethodSection = false
	for (ifaceStr in ifaceOrder) {
		val methods = methodsByIface[ifaceStr] ?: continue
		if (!hasMethodSection) impl.appendLine()
		impl.appendLine(boxSection("implements $ifaceStr"))
		impl.appendLine()
		for (m in methods) emitMethod(className, m, suppressHdr = true, ifaceName = ifaceStr)
		hasMethodSection = true
		}
	val otherMethods = methodsByIface[""] ?: emptyList()
	if (otherMethods.isNotEmpty()) {
		if (!hasMethodSection) impl.appendLine()
		impl.appendLine(boxSection("methods"))
		impl.appendLine()
		for (m in otherMethods) emitMethod(className, m, suppressHdr = false, ifaceName = "")
		hasMethodSection = true
		}
	popScope()
	currentClass = null
	return hasMethodSection
	}

/**
 * Emits explicit overrides of Any methods (dispose / toString / hashCode) for a class.
 * Manages [currentClass], [selfIsPointer], [pushScope]/[popScope].
 */
/**
 * Emits `return null;` equivalent if the current function returns a nullable type and no explicit return was emitted.
 * Call after emitting the last statement of a function body when the last statement is not a ReturnStmt.
 */
internal fun CCodeGen.emitImplicitNullReturn(ind: String) {
	if (!currentFnReturnsNullable) return
	if (currentFnReturnKtcType is KtcType.Any) impl.appendLine("${ind}return (ktc_Any){0};")
	else impl.appendLine("${ind}return ${optNone(currentFnOptReturnCTypeName)};")
	}

internal fun CCodeGen.emitClassAnyOverrides(className: String, members: List<Decl>, ci: ClassInfo) {
	val anyMethodNames = kAnyMethodNames
	currentClass  = className
	selfIsPointer = true
	pushScope()
	registerClassFields(ci, "\$self->")
	for (m in members) {
		if (m is FunDecl && m.receiver == null && m.name in anyMethodNames) {
			// Emit custom toString as (self, sb) to avoid per-call allocation
			if (m.name == "toString" && m.returnType != null) {
				emitToStringOverride(className, m, ci)
				} else {
				emitMethod(className, m, suppressHdr = false, ifaceName = "")
				}
			}
		}
	popScope()
	currentClass = null
	}

/** Emit a custom toString() override in the standard (Foo* self, ktc_StrBuf* sb) form. */
private fun CCodeGen.emitToStringOverride(className: String, f: FunDecl, ci: ClassInfo) {
	val cName   = ci.flatName
	val maxLen  = toStringMaxLen(className)
	val maxCmnt = if (maxLen != null) " // max output: $maxLen chars" else ""
	hdr.appendLine("KTC_METHOD(void, toString)(KTC_TYPE_NAME* \$self, ktc_StrBuf* sb);${maxCmnt}")
	impl.appendLine("// ══ override fun toString() ══")
	impl.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb) {")

	val prevState = saveFunState()
	currentClass = className
	selfIsPointer = true
	pushScope()
	registerClassFields(ci, "\$self->")

	// Emit the body's return expression directly into sb
	val vLast = f.body?.stmts?.lastOrNull()
	val vRetExpr = when (vLast) {
		is ReturnStmt -> vLast.value
		is ExprStmt  -> vLast.expr
		else         -> null
		}
	if (vRetExpr is StrTemplateExpr) {
		genStrTemplateToSb(vRetExpr, "sb")
		} else if (vRetExpr != null) {
		val vStr = genExpr(vRetExpr)
		preStmts += "ktc_core_sb_append_str(sb, $vStr);"
		}
	flushPreStmts("    ")

	popScope()
	restoreFunState(prevState)
	impl.appendLine("}")
	impl.appendLine()
	}
