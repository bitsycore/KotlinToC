package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// Shared helpers for function/method emit — return-type analysis, scope registration,
// and class method section grouping.

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
		defineVar(p.name, when {
			p.isVararg      -> "${str}Array"
			p.type.nullable -> "${str}?"
			else            -> str
			})
		if (p.type.nullable && isValueNullableKtc(KtcType.Nullable(ktc))) markOptional(p.name)
		}
	}

/**
 * Registers all class fields from [ci] in the current scope as [LocalVar] descriptors.
 * [selfPrefix] is the C access prefix — use `"\$self->"` for pointer self, `"\$self."` for value self.
 */
internal fun CCodeGen.registerClassFields(ci: ClassInfo, selfPrefix: String) {
	for ((name, type) in ci.props) {
		val ktc        = resolveTypeName(type)
		val cFieldName = if (name in ci.privateProps) "PRIV_$name" else name
		val isOpt      = type.nullable && !type.annotations.any { it.name == "Ptr" } && !ktc.isArrayLike
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
	val anyMethodNames = setOf("dispose", "toString", "hashCode")
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
	val anyMethodNames = setOf("dispose", "toString", "hashCode")
	currentClass  = className
	selfIsPointer = true
	pushScope()
	registerClassFields(ci, "\$self->")
	for (m in members) {
		if (m is FunDecl && m.receiver == null && m.name in anyMethodNames)
			emitMethod(className, m, suppressHdr = false, ifaceName = "")
		}
	popScope()
	currentClass = null
	}
