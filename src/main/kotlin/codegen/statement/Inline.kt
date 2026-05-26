package com.bitsycore.ktc.codegen.statement

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.types.KtcType

// ── Inline and lambda call expansion ─────────────────────────────

/* Expand an inline function body at the call site.
receiverExpr: C expression for `this` inside an extension fun body.
resultVar: when non-null, `return` inside the body assigns here (value position).
           when null, the body is expanded for side effects (statement position).
A unique goto label is emitted after the block so that `return` inside the body
jumps to the end without exiting the enclosing C function. */
internal fun CCodeGen.emitInlineCall(
	decl: FunDecl,
	callArgs: List<Arg>,
	ind: String,
	method: Boolean,
	receiverExpr: String? = null,
	receiverType: String? = null,
	resultVar: String? = null
	) {
	val body = decl.body ?: return
	val vInlineId  = inlineCounter++
	val vLabelName = "\$end_ir_$vInlineId"
	// Build comment with template types (clear typeSubst so type params appear unsubstituted)
	val vSavedSubstForComment = typeSubst
	typeSubst = emptyMap()
	val vSig = buildString {
		if (receiverExpr != null) append("$receiverExpr.")
		append(decl.name)
		append("(")
		callArgs.forEachIndexed { idx, a ->
			if (idx > 0) append(", ")
			val vP     = decl.params.getOrNull(idx)
			val vPName = vP?.name ?: "arg$idx"
			if (a.expr is LambdaExpr) {
				val vPType = vP?.type?.let { resolveTypeName(it).toInternalStr } ?: "?"
				append("$vPName = $vPType")
				} else {
				val vExprStr = when (a.expr) {
					is NameExpr -> a.expr.name
					is ThisExpr -> "this"
					is IntLit   -> a.expr.value.toString()
					is StrLit   -> "\"${a.expr.value}\""
					is BoolLit  -> a.expr.value.toString()
					else        -> "..."
					}
				append("$vPName = $vExprStr")
				}
			}
		append(")")
		decl.returnType?.let { append(": ${resolveTypeName(it).toInternalStr}") }
		}
	typeSubst = vSavedSubstForComment
	// Flush any preStmts accumulated from the caller's earlier arg evaluation
	// (e.g. iface trampoline temps from a previous sibling argument). They
	// belong in the enclosing scope; leaving them would land them inside this
	// inline body's `{ }` and put them out of scope for the outer call.
	flushPreStmts(ind)
	impl.appendLine("$ind/* inline $vSig */")
	impl.appendLine("$ind{")
	pushScope()
	val vSavedLambdas  = activeLambdas
	val vNewLambdas    = activeLambdas.toMutableMap()
	val vSavedRetVar    = inlineReturnVar
	val vSavedEndLabel  = inlineEndLabel
	val vSavedLabelUsed = inlineLabelUsed
	inlineReturnVar  = resultVar ?: ""
	inlineEndLabel   = vLabelName
	inlineLabelUsed  = false

	// Set up `this` substitution for extension function receivers
	val vSavedThis     = lambdaParamSubst["\$this"]
	val vSavedThisType = lambdaParamTypes["\$this"]
	if (receiverExpr != null) lambdaParamSubst["\$this"] = receiverExpr
	if (receiverType != null) lambdaParamTypes["\$this"] = receiverType

	// Bind each parameter: lambda params go into activeLambdas, value params become locals.
	// Two-pass approach: evaluate all argument expressions first, then declare parameter variables.
	// This prevents C self-initialization UB when a param name shadows an outer variable of the
	// same name (e.g. `rotr(x, n)` called with arg `x` would emit `ktc_Int x = x;` where the
	// right-hand `x` would refer to the newly declared uninitialized variable, not the outer one).
	data class BoundParam(
		val cTypeName: String,
		val paramName: String,
		val cVal: String,
		val scopeKtc: KtcType,
		val isNullable: Boolean
		)
	val vBoundParams = mutableListOf<BoundParam>()
	callArgs.forEachIndexed { i, vArg ->
		val vParam = decl.params.getOrNull(i) ?: return@forEachIndexed
		val vExpr  = vArg.expr
		if (vExpr is LambdaExpr) {
			val vFuncParams = vParam.type.funcParams ?: emptyList()
			val vParamTypes = vFuncParams.map { resolveTypeName(it) }
			val vRetType    = vParam.type.funcReturn?.let { resolveTypeName(it) }
			vNewLambdas[vParam.name] = ActiveLambda(vExpr, vParamTypes, vRetType)
			} else {
			val vResolvedKtc    = resolveTypeName(vParam.type)
			val vIsValueNullable = vParam.type.nullable && !vParam.type.isRefType()
			val (vCTypeName, vScopeKtc) = if (vIsValueNullable) {
				val vInnerKtc = resolveTypeName(vParam.type.copy(nullable = false))
				optCTypeName(vInnerKtc.toInternalStr) to KtcType.Nullable(vInnerKtc)
				} else {
				cTypeStr(vResolvedKtc) to vResolvedKtc
				}
			val vCVal = genExpr(vExpr)
			flushPreStmts(ind)
			vBoundParams.add(BoundParam(vCTypeName, vParam.name, vCVal, vScopeKtc, vIsValueNullable))
			}
		}
	for (vBp in vBoundParams) {
		val vCName = "\$il${vInlineId}_${vBp.paramName}"
		impl.appendLine("$ind    ${vBp.cTypeName} $vCName = ${vBp.cVal};")
		defineVar(vBp.paramName, LocalVar(vBp.scopeKtc, cName = vCName))
		if (vBp.isNullable) markOptional(vBp.paramName)
		}
	activeLambdas = vNewLambdas

	emitBlock(body, ind, method)

	if (inlineLabelUsed) impl.appendLine("$ind$vLabelName:;")
	activeLambdas   = vSavedLambdas
	inlineReturnVar  = vSavedRetVar
	inlineEndLabel   = vSavedEndLabel
	inlineLabelUsed  = vSavedLabelUsed
	if (receiverExpr != null) {
		if (vSavedThis != null) lambdaParamSubst["\$this"] = vSavedThis else lambdaParamSubst.remove("\$this")
		}
	if (receiverType != null) {
		if (vSavedThisType != null) lambdaParamTypes["\$this"] = vSavedThisType else lambdaParamTypes.remove("\$this")
		}
	popScope()
	// Smart cast propagation: if a nullable param was null-checked and bound to
	// a simple NameExpr, narrow the argument in the caller scope (e.g. checkNotNull(x)).
	val vPropCasts = mutableListOf<Pair<String, String>>()
	for ((i, vArg) in callArgs.withIndex()) {
		val vParam  = decl.params.getOrNull(i) ?: continue
		if (vArg.expr !is NameExpr) continue
		val vArgName = vArg.expr.name
		if (isMutable(vArgName)) continue  // var cannot be smart-cast
		val vParamKtc = resolveTypeName(vParam.type.copy(nullable = false))
		val vRetKtc   = decl.returnType?.let { resolveTypeName(it) }
		if (vParam.type.nullable && vRetKtc != null
			&& vRetKtc.toInternalStr == vParamKtc.toInternalStr) {
			narrowVarType(vArgName, vRetKtc.toInternalStr)
			vPropCasts.add(vArgName to vRetKtc.toInternalStr)
			}
		}
	impl.appendLine("$ind}")
	for ((vName, vNarrowedType) in vPropCasts) {
		impl.appendLine("$ind// smart-cast: '$vName' narrowed to '$vNarrowedType'")
		}
	}

/* Expand a lambda call inside an inline body (statement position).
Lambda params are substituted via lambdaParamSubst rather than declared as C variables,
avoiding name-collision issues when lambda params shadow enclosing inline params. */
internal fun CCodeGen.emitLambdaCall(active: ActiveLambda, callArgs: List<Arg>, ind: String) {
	val vSavedSubst = lambdaParamSubst.toMap()
	val vSavedTypes = lambdaParamTypes.toMap()
		active.expr.params.forEachIndexed { i, pName ->
			val vArg = callArgs.getOrNull(i)
			if (vArg != null) {
				var vSubst = genExpr(vArg.expr)
				val vParamKtc = active.paramTypes.getOrNull(i)
				// Unwrap c.addr(x) → &x when param is Ref<T>: use x directly
				if (vParamKtc is KtcType.Ptr && vSubst.startsWith("&")) {
					vSubst = vSubst.removePrefix("&")
					}
				lambdaParamSubst[pName] = vSubst
				val vT = (if (vArg.expr is ThisExpr) lambdaParamTypes["\$this"] else null)
					?: inferExprType(vArg.expr)
					?: active.paramTypes.getOrNull(i)?.toInternalStr ?: ""
				if (vT.isNotEmpty()) lambdaParamTypes[pName] = vT
				}
			}
	for (stmt in active.expr.body) emitStmt(stmt, ind)
	lambdaParamSubst.clear(); lambdaParamSubst.putAll(vSavedSubst)
	lambdaParamTypes.clear(); lambdaParamTypes.putAll(vSavedTypes)
	}
