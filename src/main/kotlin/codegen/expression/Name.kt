package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.CCodeGen
import com.bitsycore.ktc.codegen.cTypeStr
import com.bitsycore.ktc.codegen.inferExprTypeKtc
import com.bitsycore.ktc.codegen.statement.emitStmt
import com.bitsycore.ktc.codegen.stripNullable
import com.bitsycore.ktc.types.KtcType

// ── Name resolution and l-value generation ───────────────────────

/* Resolve a name expression: lambda subst, scope variable, field, object, enum, top-level prop. */
internal fun CCodeGen.genName(e: NameExpr): String {
	val vSubst  = lambdaParamSubst[e.name]
	if (vSubst != null) return vSubst
	// Lazy local variable: emit init-check before the expression
	if (e.name in lazyInitBlocks) {
		emitLazyInitCheck(e.name)
		return "${e.name}\$cache"
	}
	// Lazy top-level property: call ensure_init before access
	if (e.name in lazyTopProps) {
		preStmts += "${typeFlatName(e.name)}_\$ensure_init();"
		return "${typeFlatName(e.name)}\$cache"
	}
	val vCurType = lookupVar(e.name)
	val vCurKtc  = lookupVarKtc(e.name)
	// Check if it's a known variable in scope
	if (vCurType != null) {
		// Trampolined array param: redirect to local stack copy
		if (e.name in trampolinedParams) return "local$${e.name}"
			// Any trampoline smart-cast: narrowed from Any, dereference .data
			if (vCurKtc !is KtcType.Any && isAnySmartCastVar(e.name) && vCurKtc !is KtcType.Ptr) {
				val vCt = cTypeStr(vCurType)
				// Outer-scope type before narrowing: Ptr(Any) → use ->data
					val vOuterKtc = scopes.getOrNull(scopes.size - 2)?.get(e.name)?.ktc
					val vMemOp = if (vOuterKtc is KtcType.Ptr) "->" else "."
				return "(*(($vCt*)(${e.name}${vMemOp}data)))"
				}
			// Ref<Any> narrowed to Ref<Concrete>: cast the pointer
			if (vCurKtc is KtcType.Ptr && isAnySmartCastVar(e.name)) {
				val vCt = vCurKtc.toCType()
				return "(($vCt)${e.name})"
				}
		val vCExpr = lookupCName(e.name)
		// Optional var smart-casted to non-nullable: unwrap
		if (isOptional(e.name) && vCurKtc !is KtcType.Nullable) {
			return "KTC_UNWRAP($vCExpr)"
			}
		return vCExpr
		}
	// Top-level property: apply package prefix
	if (e.name in topProps) return typeFlatName(e.name)
	// Object singleton: resolve to global instance name
	if (e.name in objects) return typeFlatName(e.name)
	if (e.name in enums)   return typeFlatName(e.name)
	// Bare field access when $self has been narrowed from interface in extension function
	if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
		val vNarrowedSelf = lookupVar("\$self")
		if (vNarrowedSelf != null && classes.containsKey(vNarrowedSelf)) {
			val vCi = classes[vNarrowedSelf]!!
			if (vCi.props.any { it.first == e.name }) {
				return "${ifaceUnionAccess(currentExtRecvType!!, vNarrowedSelf, "\$self")}.${e.name}"
				}
			}
		}
	// Bare field access inside a class method or class-extension function body:
	// resolve `field` to `$self.field` / `$self->field` based on selfIsPointer.
	if (currentClass != null) {
		val vCi = classes[currentClass]
		if (vCi != null && vCi.props.any { it.first == e.name }) {
			val vProp = vCi.properties.find { it.name == e.name }
			// Computed property (custom getter) — recurse through genDot so the
			// sibling getter is expanded inline. When invoked during a getter
			// expansion at a user call site, the recorded receiver replaces the
			// literal `$self` lookup that would otherwise dangle.
			if (vProp != null && vProp.getter != null) {
				val vRecvExpr = getterReceiverStack.lastOrNull()
				if (vRecvExpr != null) {
					return genDot(DotExpr(NameExpr(vRecvExpr), e.name))
					}
				}
			val vFieldName = if (e.name in vCi.privateProps) "PRIV_${e.name}" else e.name
			val vOp = if (selfIsPointer) "->" else "."
			return "\$self$vOp$vFieldName"
			}
		// Bare enum entry inside an enum method (e.g. `PLUS` in `when (this) { PLUS -> ... }`):
		// resolve to the qualified const struct (e.g. `prefix_Op_PLUS`).
		val vEi = enums[currentClass]
		if (vEi != null && e.name in vEi.entries) return "${vEi.flatName}_${e.name}"
		}
	return e.name
	}

/* Generate the C l-value expression for an assignment target. */
internal fun CCodeGen.genLValue(e: Expr): String {
	return when (e) {
		is NameExpr -> lookupCName(e.name)

		is DotExpr -> {
			if (e.obj is NameExpr && objects.containsKey(e.obj.name))
				"${typeFlatName(e.obj.name)}.${e.name}"
			else if (e.obj is NameExpr && classCompanions.containsKey(e.obj.name)) {
				val vCompanionName = classCompanions[e.obj.name]!!
				"${typeFlatName(vCompanionName)}.${e.name}"
				} else {
				val vRecvKtc     = inferExprTypeKtc(e.obj)
				val vRecvKtcCore = vRecvKtc.stripNullable
				val vOp          = if (vRecvKtcCore is KtcType.Ptr) "->" else "."
				"${genExpr(e.obj)}$vOp${e.name}"
				}
			}

		is IndexExpr -> {
			val vObjKtc        = inferExprTypeKtc(e.obj)
			val vObjKtcCore    = vObjKtc.stripNullable
			val vIsRawPtr      = vObjKtcCore is KtcType.Ptr && vObjKtcCore.inner !is KtcType.Arr // raw pointer (not Ref<Array<T>>)
			val vIsSizedArr    = vObjKtcCore?.asArr?.sized != null                             // fixed-size C array
			val vIsTrampolined = e.obj is NameExpr && e.obj.name in trampolinedParams                             // @Size trampolined param
			if (vIsRawPtr || vIsSizedArr || vIsTrampolined) "${genExpr(e.obj)}[${genExpr(e.index)}]"
			else "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
			}

		else -> genExpr(e)
		}
	}

// Emit the lazy init-check for a local variable into preStmts
private fun CCodeGen.emitLazyInitCheck(name: String) {
	val block = lazyInitBlocks[name] ?: return
	val stmts = block.stmts
	val lastStmt = stmts.lastOrNull() ?: return
	val lastExpr = when (lastStmt) {
		is ExprStmt   -> lastStmt.expr
		is ReturnStmt -> lastStmt.value
		else          -> null
	} ?: return

	// Save current preStmts, generate the init expression (which may produce its own preStmts)
	val savedPreStmts = preStmts.toList()
	preStmts.clear()

	// Generate intermediate statements (all except the last) into a temporary impl section
	val savedImpl = impl
	val tmpImpl = StringBuilder()
	impl = tmpImpl
	for (i in 0 until stmts.size - 1) {
		emitStmt(stmts[i], "")
	}
	impl = savedImpl

	// Generate the last expression
	val initExpr = genExpr(lastExpr)
	val initPreStmts = preStmts.toList()
	preStmts.clear()

	// Restore saved preStmts and build the lazy init block
	preStmts.addAll(savedPreStmts)
	preStmts += "if (!${name}\$inited) {"
	// Intermediate statements from the body
	for (line in tmpImpl.toString().lines().filter { it.isNotBlank() }) {
		preStmts += "    $line"
	}
	// Pre-statements from the last expression
	for (s in initPreStmts) {
		preStmts += "    $s"
	}
	preStmts += "    ${name}\$cache = $initExpr;"
	preStmts += "    ${name}\$inited = true;"
	preStmts += "}"
}
