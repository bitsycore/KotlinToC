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
	val vLabelName = "\$end_ir_${inlineCounter++}"
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
	impl.appendLine("$ind/* inline $vSig */")
	impl.appendLine("$ind{")
	pushScope()
	val vSavedLambdas  = activeLambdas
	val vNewLambdas    = activeLambdas.toMutableMap()
	val vSavedRetVar   = inlineReturnVar
	val vSavedEndLabel = inlineEndLabel
	inlineReturnVar = resultVar ?: ""
	inlineEndLabel  = vLabelName

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
			val vIsValueNullable = vParam.type.nullable && !vParam.type.annotations.any { it.name == "Ptr" }
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
		// If cVal equals paramName, declaring `T x = x;` in C causes self-initialization UB
		// because the new variable's scope starts after the declarator, shadowing the outer one.
		// Capture the outer value in a temp first.
		val vFinalVal = if (vBp.cVal == vBp.paramName) {
			val vTmp = "\$ptmp_${vBp.paramName}"
			impl.appendLine("$ind    ${vBp.cTypeName} $vTmp = ${vBp.cVal};")
			vTmp
			} else {
			vBp.cVal
			}
		impl.appendLine("$ind    ${vBp.cTypeName} ${vBp.paramName} = $vFinalVal;")
		defineVarKtc(vBp.paramName, vBp.scopeKtc)
		if (vBp.isNullable) markOptional(vBp.paramName)  // must come after defineVarKtc
		}
	activeLambdas = vNewLambdas

	emitBlock(body, ind, method)

	impl.appendLine("$ind$vLabelName:;")
	activeLambdas = vSavedLambdas
	inlineReturnVar = vSavedRetVar
	inlineEndLabel  = vSavedEndLabel
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
			lambdaParamSubst[pName] = genExpr(vArg.expr)
			// For ThisExpr args inside inline bodies, inferExprType returns null (no C $self scope);
			// fall back to lambdaParamTypes["\$this"] which was set by emitInlineCall's receiverType
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
