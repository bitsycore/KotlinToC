package com.bitsycore.ktc.codegen.statement

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.codegen.expression.genClosureValue
import com.bitsycore.ktc.types.KtcType

// ── Inline and lambda call expansion ─────────────────────────────

/* Expand an inline function body at the call site.
receiverExpr: C expression for `this` inside an extension fun body.
resultVar: when non-null, `return` inside the body assigns here (value position).
           when null, the body is expanded for side effects (statement position).
A unique goto label is emitted after the block so that `return` inside the body
jumps to the end without exiting the enclosing C function. */
private fun CCodeGen.buildInlineComment(
	decl: FunDecl,
	callArgs: List<Arg>,
	receiverExpr: String? = null
): String {
	val vSavedSubst = typeSubst
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
	typeSubst = vSavedSubst
	return vSig
}

internal fun CCodeGen.emitInlineCall(
	decl: FunDecl,
	callArgs: List<Arg>,
	ind: String,
	method: Boolean,
	receiverExpr: String? = null,
	receiverType: String? = null,
	resultVar: String? = null,
	resultOptCType: String? = null,
	resultUnionType: String? = null
	) {
	val body = decl.body ?: return
	val vInlineId  = inlineCounter++
	val vLabelName = "\$end_ir_$vInlineId"
	val vSig = buildInlineComment(decl, callArgs, receiverExpr)
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
	val vSavedRetOpt    = inlineReturnOptCType
	val vSavedRetUnion  = inlineReturnUnionType
	val vSavedEndLabel  = inlineEndLabel
	val vSavedLabelUsed = inlineLabelUsed
	val vSavedTryMark   = inlineTryMark
	inlineReturnVar      = resultVar ?: ""
	inlineReturnOptCType = resultOptCType
	inlineReturnUnionType = resultUnionType
	inlineEndLabel   = vLabelName
	inlineLabelUsed  = false
	inlineTryMark    = tryContexts.size   // a return in this body only unwinds tries opened inside it

	// Set up `this` substitution for extension function receivers
	val vSavedThis     = lambdaParamSubst["\$this"]
	val vSavedThisType = lambdaParamTypes["\$this"]
	if (receiverExpr != null) lambdaParamSubst["\$this"] = receiverExpr
	if (receiverType != null) {
		lambdaParamTypes["\$this"] = receiverType
		defineVar("\$this", receiverType)
		}

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
		if (vExpr is LambdaExpr && vParam.noinline) {
			// `noinline` param: the lambda is NOT inlined — it becomes a real (frame-bound) closure the
			// body can move around, exactly like a non-inline function's closure param. Build the functor
			// at the call site and bind the param to that local (so `param(x)` in the body dispatches
			// through its _invoke). The closure instance name is unique, so it can't shadow anything.
			val vFuncType = resolveTypeName(vParam.type) as? KtcType.Func
				?: codegenError("noinline parameter '${vParam.name}' is not a function type")
			val (vStruct, vCloInst) = genClosureValue(vExpr, vFuncType)
			flushPreStmts(ind)
			defineVar(vParam.name, LocalVar(parseResolvedTypeName(vStruct), cName = vCloInst))
			} else if (vExpr is LambdaExpr) {
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
	activeLambdas        = vSavedLambdas
	inlineReturnVar      = vSavedRetVar
	inlineReturnOptCType = vSavedRetOpt
	inlineReturnUnionType = vSavedRetUnion
	inlineEndLabel       = vSavedEndLabel
	inlineLabelUsed      = vSavedLabelUsed
	inlineTryMark        = vSavedTryMark
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

/* Try to collapse a trivial inline function (single return expression, no lambda
args) into a direct expression evaluation — avoids the $ir temp var, { } block,
and goto label, producing cleaner emitted C. Returns the C expression string, or
null if the body isn't trivially collapsible. */
internal fun CCodeGen.tryGenInlineExpr(
	decl: FunDecl,
	callArgs: List<Arg>,
	receiverExpr: String? = null,
	receiverType: String? = null
): String? {
	val body = decl.body ?: return null
	if (body.stmts.size != 1) return null
	val retStmt = body.stmts[0] as? ReturnStmt ?: return null
	val retExpr = retStmt.value ?: return null
	if (callArgs.any { it.expr is LambdaExpr }) return null

	val vInlineId = inlineCounter++
	val ind = currentInd
	flushPreStmts(ind)
	val vSig = buildInlineComment(decl, callArgs, receiverExpr)
	impl.appendLine("$ind/* inline $vSig */")

	val vSavedThis     = lambdaParamSubst["\$this"]
	val vSavedThisType = lambdaParamTypes["\$this"]
	if (receiverExpr != null) lambdaParamSubst["\$this"] = receiverExpr
	if (receiverType != null) lambdaParamTypes["\$this"] = receiverType

	pushScope()
	// Shadow any outer `$this` binding from an enclosing inline call so this inline's
	// receiver type wins for `this.x` lookups (otherwise nested inline-expansions resolve
	// `this` to the wrong type — e.g. drawRoundedRect.this leaks into the arg-eval scope
	// where box.grow() is being inlined, and `this.x` would resolve via the outer receiver).
	if (receiverType != null) defineVar("\$this", receiverType)

	callArgs.forEachIndexed { i, arg ->
		val param = decl.params.getOrNull(i) ?: return@forEachIndexed
		val resolvedKtc    = resolveTypeName(param.type)
		val isValueNullable = param.type.nullable && !param.type.isRefType()
		val (cTypeName, scopeKtc) = if (isValueNullable) {
			val innerKtc = resolveTypeName(param.type.copy(nullable = false))
			optCTypeName(innerKtc.toInternalStr) to KtcType.Nullable(innerKtc)
		} else {
			cTypeStr(resolvedKtc) to resolvedKtc
		}
		val cVal  = genExpr(arg.expr)
		flushPreStmts(ind)
		val cName = "\$il${vInlineId}_${param.name}"
		impl.appendLine("$ind$cTypeName $cName = $cVal;")
		defineVar(param.name, LocalVar(scopeKtc, cName = cName))
		if (isValueNullable) markOptional(param.name)
	}

	var result = genExpr(retExpr)
	flushPreStmts(ind)

	// @SimpleUnion: when the inline return type is a sealed interface but the expression
	// produces a subclass, wrap with the subclass_as_interface conversion
	if (decl.returnType != null) {
		val retIfaceType = resolveTypeName(decl.returnType).toInternalStr
		val exprType = inferExprType(retExpr)
		if (exprType != null && exprType != retIfaceType && interfaces.containsKey(retIfaceType) &&
			classes.containsKey(exprType) && classInterfaces[exprType]?.contains(retIfaceType) == true) {
			val backing = tmp()
			impl.appendLine("$ind${typeFlatName(exprType)} $backing = $result;")
			result = "${typeFlatName(exprType)}_as_$retIfaceType(&$backing)"
		}
	}

	popScope()

	if (receiverExpr != null) {
		if (vSavedThis != null) lambdaParamSubst["\$this"] = vSavedThis
		else lambdaParamSubst.remove("\$this")
	}
	if (receiverType != null) {
		if (vSavedThisType != null) lambdaParamTypes["\$this"] = vSavedThisType
		else lambdaParamTypes.remove("\$this")
	}

	return result
}

/* Infer the type a lambda body evaluates to — the type of its last expression, or "Unit" when the
body ends in a statement (assignment, loop, bare `return`) or is empty. Returns null only when the
body ends in an expression whose type can't be inferred (so the caller leaves the type param unbound
rather than guessing). Used to bind an inline function's type parameter that appears only in a lambda
parameter's return position (`block: () -> R`) — the value-type-only call-site inference can't see it,
since a lambda argument itself infers to null. */
internal fun CCodeGen.inferLambdaReturnType(
	inLambda:       LambdaExpr,
	inParamKtc:     List<KtcType>,
	inReceiverType: String?
	): String? {
	pushScope()
	inLambda.params.forEachIndexed { vI, vP -> inParamKtc.getOrNull(vI)?.let { defineVarKtc(vP, it) } }
	// Implicit single `it` when the function type has one param and the lambda omits names.
	if (inLambda.params.isEmpty() && inParamKtc.size == 1) defineVarKtc("it", inParamKtc[0])
	if (inReceiverType != null) defineVar("\$this", inReceiverType)
	val vType = when (val vLast = inLambda.body.lastOrNull()) {
		is ExprStmt   -> inferExprType(vLast.expr)                                  // null = un-inferable
		is ReturnStmt -> if (vLast.value == null) "Unit" else inferExprType(vLast.value)
		null          -> "Unit"                                                     // empty body
		else          -> "Unit"                                                     // trailing statement
		}
	popScope()
	return vType
	}

/* Bind an inline function's type parameters that appear in a lambda parameter's return position
(`block: () -> R`) by inferring each lambda argument's body type. Mutates [ioSubst]; the caller runs
value-type (receiver/arg) inference first, so an already-bound param is left untouched. */
internal fun CCodeGen.bindLambdaReturnTypeParams(
	inDecl:         FunDecl,
	inArgs:         List<Arg>,
	inReceiverType: String?,
	ioSubst:        MutableMap<String, String>
	) {
	val vTypeParams = inDecl.typeParams.toSet()
	if (vTypeParams.isEmpty()) return
	inDecl.params.forEachIndexed { vI, vParam ->
		val vRet = vParam.type.funcReturn ?: return@forEachIndexed                  // not a function type
		if (vRet.name !in vTypeParams || ioSubst.containsKey(vRet.name)) return@forEachIndexed
		val vLambda = inArgs.getOrNull(vI)?.expr as? LambdaExpr ?: return@forEachIndexed
		val vParamKtc      = (vParam.type.funcParams ?: emptyList()).map { resolveTypeName(it) }
		val vRecvForLambda = if (vParam.type.funcReceiver != null) inReceiverType else null
		val vInferred      = inferLambdaReturnType(vLambda, vParamKtc, vRecvForLambda) ?: return@forEachIndexed
		ioSubst[vRet.name] = vInferred
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
