package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.emit.ifaceDataName
import com.bitsycore.ktc.types.KtcType

/**
 * ── Statement Codegen ───────────────────────────────────────────────────
 *
 * Core dispatcher and inline/lambda expansion. Specialized statement handlers
 * live in focused sub-files:
 *
 *   [emitStmt]        — statement dispatcher (routes to all handlers below)
 *   [emitBlock]       — emit a block of statements
 *   [emitExprStmt]    — expression statements, inline/lambda call expansion, print
 *   [emitInlineCall]  — inline function expansion at call site
 *   [emitLambdaCall]  — lambda expansion inside inline body
 *
 * Handlers in other files (same package):
 *   StmtsVar.kt       — var/val declarations (emitVarDecl, tryArrayOfInit, ...)
 *   StmtsAssign.kt    — assignment and return (emitAssign, emitReturn)
 *   StmtsPrint.kt     — print/println (emitPrintlnStmt, emitPrintStmtInner, ...)
 *   StmtsControl.kt   — if/when with smart-casts (emitIfStmt, emitWhenStmt, ...)
 *   StmtsFor.kt       — for loops (emitFor, findOperatorIterator)
 */

// ═══════════════════════════ Statements ═══════════════════════════

internal fun CCodeGen.emitStmt(s: Stmt, ind: String, insideMethod: Boolean = false) {
    if (s.line > 0) currentStmtLine = s.line
    currentInd = ind
    when (s) {
        is VarDeclStmt -> emitVarDecl(s, ind)
        is AssignStmt -> emitAssign(s, ind, insideMethod)
        is ReturnStmt -> emitReturn(s, ind)
        is ExprStmt -> emitExprStmt(s, ind, insideMethod)
        is ForStmt -> emitFor(s, ind, insideMethod)
        is WhileStmt -> {
            loopDepth++
            impl.appendLine("${ind}while (${genExprFlushed(s.cond, ind)}) {")
            emitBlock(s.body, ind, insideMethod)
            impl.appendLine("$ind}")
            loopDepth--
        }

        is DoWhileStmt -> {
            loopDepth++
            impl.appendLine("${ind}do {")
            emitBlock(s.body, ind, insideMethod)
            impl.appendLine("$ind} while (${genExprFlushed(s.cond, ind)});")
            loopDepth--
        }

        is BreakStmt -> {
            if (loopDepth == 0) codegenError("'break' outside of a loop")
            impl.appendLine("${ind}break;")
        }
        is ContinueStmt -> {
            if (loopDepth == 0) codegenError("'continue' outside of a loop")
            impl.appendLine("${ind}continue;")
        }
        is DeferStmt -> deferStack.add(s.body)
        is CommentStmt -> {
            impl.appendLine("$ind${s.text}")
        }
    }
    // Smart cast: if (x == null) return/break/continue → narrow x to non-null after
    applyGuardSmartCast(s)
}

/** After `if (x == null) ... return/break/continue` (no else), narrow x from T? to T. */
internal fun CCodeGen.applyGuardSmartCast(s: Stmt) {
    if (s !is ExprStmt) return
    val ifExpr = s.expr as? IfExpr ?: return
    if (ifExpr.els != null) return  // must have no else branch
    // Body must end with an early-exit statement
    val lastStmt = ifExpr.then.stmts.lastOrNull() ?: return
    if (lastStmt !is ReturnStmt && lastStmt !is BreakStmt && lastStmt !is ContinueStmt) return
    val casts = extractElseSmartCasts(ifExpr.cond)
    val outerInd = currentInd.removeSuffix("    ")
    for ((name, nonNullType) in casts) {
        impl.appendLine("${outerInd}// smart-cast: '$name' narrowed to '$nonNullType'")
        defineVar(name, nonNullType)
    }
}

internal fun CCodeGen.emitBlock(b: Block, ind: String, insideMethod: Boolean = false) {
    for ((idx, s) in b.stmts.withIndex()) {
        emitStmt(s, "$ind    ", insideMethod)
        // Check: unreachable code after unconditional exit
        if (s is ReturnStmt || s is BreakStmt || s is ContinueStmt) {
            val vRemaining = b.stmts.drop(idx + 1).filter { it !is CommentStmt }
            if (vRemaining.isNotEmpty()) {
                if (s.line > 0) currentStmtLine = s.line
                codegenError("Unreachable code after '${if (s is ReturnStmt) "return" else if (s is BreakStmt) "break" else "continue"}'")
            }
        }
    }
}

// ── expression statement (may be println, method call, etc.) ─────

internal fun CCodeGen.emitExprStmt(s: ExprStmt, ind: String, method: Boolean) {
    val e = s.expr
    // if / when used as statements
    if (e is IfExpr) {
        emitIfStmt(e, ind, method); return
    }
    if (e is WhenExpr) {
        emitWhenStmt(e, ind, method); return
    }
    // Inline function call — expand body at call site
    if (e is CallExpr && e.callee is NameExpr) {
        val name = e.callee.name
        val inlineCandidates = inlineFunDecls[name]
        val inlineDecl = when {
            inlineCandidates == null -> null
            inlineCandidates.size == 1 -> inlineCandidates[0]
            else -> {
                val exact = inlineCandidates.find { it.params.size == e.args.size }
                exact ?: inlineCandidates.minByOrNull { kotlin.math.abs(it.params.size - e.args.size) }
            }
        }
        if (inlineDecl != null) {
            // Set up typeSubst for generic inline functions
            val vSavedSubst = typeSubst
            if (inlineDecl.typeParams.isNotEmpty()) {
                val vSubst = mutableMapOf<String, String>()
                for ((i, param) in inlineDecl.params.withIndex()) {
                    if (i >= e.args.size) break
                    val argType = inferExprType(e.args[i].expr)?.removeSuffix("?") ?: continue
                    val argTypeKtc = inferExprTypeKtc(e.args[i].expr)
                    matchTypeParam(param.type, argType, inlineDecl.typeParams.toSet(), vSubst)
                }
                if (vSubst.isNotEmpty()) typeSubst = vSubst
            }
            emitInlineCall(inlineDecl, e.args, ind, method)
            typeSubst = vSavedSubst
            return
        }
        // Active lambda call (inside an inline body expansion)
        val lambda = activeLambdas[name]
        if (lambda != null) {
            emitLambdaCall(lambda, e.args, ind); return
        }
    }
    // Inline extension function call — expand body at call site (callee is DotExpr or SafeDotExpr)
    if (e is CallExpr && (e.callee is DotExpr || e.callee is SafeDotExpr)) {
        val isSafe = e.callee is SafeDotExpr
        val methodName = if (isSafe) e.callee.name else (e.callee as DotExpr).name
        val inlineExt = inlineExtFunDecls[methodName]
        if (inlineExt != null) {
            val recvObj = if (isSafe) e.callee.obj else (e.callee as DotExpr).obj
            val recvExpr = genExpr(recvObj)
            val recvKtType = inferExprType(recvObj)?.removeSuffix("?")
            val recvKtTypeKtc = inferExprTypeKtc(recvObj)
            // Set up typeSubst for generic inline extension functions
            val vSavedSubst = typeSubst
            if (inlineExt.typeParams.isNotEmpty()) {
                val vArgTypes = e.args.map { inferExprType(it.expr) } // concrete arg types
                val vArgTypesKtc = e.args.map { inferExprTypeKtc(it.expr) }
                typeSubst = inferInlineFunSubst(inlineExt, recvKtType, vArgTypes)
            }
            if (isSafe) {
                // Safe call: guard the inline block with a null check
                val recvName = (recvObj as? NameExpr)?.name
                val recvType = if (recvName != null) lookupVar(recvName) else null
                val recvTypeKtc = if (recvType != null) parseResolvedTypeName(recvType) else null
                val guard = if (recvTypeKtc is KtcType.Nullable && isValueNullableKtc(recvTypeKtc))
                    "$recvExpr.tag == ktc_SOME"
                else
                    "$recvExpr != NULL"
                impl.appendLine("${ind}if ($guard) {")
                emitInlineCall(inlineExt, e.args, "$ind    ", method, receiverExpr = recvExpr, receiverType = recvKtType)
                impl.appendLine("$ind}")
            } else {
                emitInlineCall(inlineExt, e.args, ind, method, receiverExpr = recvExpr, receiverType = recvKtType)
            }
            typeSubst = vSavedSubst
            return
        }
    }
    // println / print as statements — avoid GCC statement-expressions
    if (e is CallExpr && e.callee is NameExpr) {
        val name = e.callee.name
        if (name == "println") {
            emitPrintlnStmt(e.args, ind); return
        }
        if (name == "print") {
            emitPrintStmt(e.args, ind); return
        }
    }
    // Heap/Ptr/Value .set(val) as statement — only when class has no own set() method
    if (e is CallExpr && e.callee is DotExpr && e.callee.name == "set") {
        val recvTypeKtc = inferExprTypeKtc(e.callee.obj)
        val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc
        val baseClass = (recvTypeCoreKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
        if (baseClass != null && classes[baseClass]?.methods?.any { it.name == "set" } != true) {
            val recv = genExpr(e.callee.obj)
            val argStr = e.args.joinToString(", ") { genExpr(it.expr) }
            flushPreStmts(ind)
            impl.appendLine("$ind*$recv = $argStr;")
            return
        }
    }
    // Safe method call as statement: a?.method() → if (guard) { method(a); }
    if (e is CallExpr && e.callee is SafeDotExpr) {
        val safe = e.callee
        val recvName = (safe.obj as? NameExpr)?.name
        val recvType = if (recvName != null) lookupVar(recvName) else null
        val recvTypeKtc = if (recvType != null) parseResolvedTypeName(recvType) else null
        if (recvType != null) {

            // Pointer-nullable (Heap<T>?, Ptr<T>?, Value<T>?, raw T*?) → NULL check
            val guard = when (recvTypeKtc) {
                is KtcType.Nullable if recvTypeKtc.inner is KtcType.Ptr ->
                    "$recvName != NULL"
                // Value-nullable Optional
                is KtcType.Nullable if isValueNullableKtc(recvTypeKtc) ->
                    "$recvName.tag == ktc_SOME"
                // Heap<T?>/Ptr<T?>/Value<T?> or other nullable
                is KtcType.Nullable -> "${recvName}\$has"
                else -> null
            }

            if (guard != null) {
                val dotExpr = DotExpr(safe.obj, safe.name)
                val callExpr = genMethodCall(dotExpr, e.args)
                flushPreStmts(ind)
                impl.appendLine("${ind}if ($guard) { $callExpr; }")
                return
            }
        }
    }
    val expr = genExpr(e)
    flushPreStmts(ind)
    impl.appendLine("$ind$expr;")
}

/* Expand an inline function call as a C block at the call site.
Non-lambda args become const-initialized locals; lambda args are
registered in activeLambdas so their call sites expand inline too. */
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
    val labelName = "\$end_ir_${inlineCounter++}"
    // Build comment with template types (clear typeSubst so type params appear unsubstituted)
    val vSavedSubstForComment = typeSubst
    typeSubst = emptyMap()
    val sig = buildString {
        if (receiverExpr != null) append("$receiverExpr.")
        append(decl.name)
        append("(")
        callArgs.forEachIndexed { idx, a ->
            if (idx > 0) append(", ")
            val p = decl.params.getOrNull(idx)
            val pName = p?.name ?: "arg$idx"
            if (a.expr is LambdaExpr) {
                val pType = p?.type?.let { resolveTypeName(it).toInternalStr } ?: "?"
                append("$pName = $pType")
            } else {
                val exprStr = when (a.expr) {
                    is NameExpr -> a.expr.name
                    is ThisExpr -> "this"
                    is IntLit -> a.expr.value.toString()
                    is StrLit -> "\"${a.expr.value}\""
                    is BoolLit -> a.expr.value.toString()
                    else -> "..."
                }
                append("$pName = $exprStr")
            }
        }
        append(")")
        decl.returnType?.let { append(": ${resolveTypeName(it).toInternalStr}") }
    }
    typeSubst = vSavedSubstForComment
    impl.appendLine("$ind/* inline $sig */")
    impl.appendLine("$ind{")
    pushScope()
    val savedLambdas = activeLambdas
    val newLambdas = activeLambdas.toMutableMap()
    val savedRetVar = inlineReturnVar
    val savedEndLabel = inlineEndLabel
    inlineReturnVar = resultVar ?: ""
    inlineEndLabel = labelName

    // Set up `this` substitution for extension function receivers
    val savedThis = lambdaParamSubst["\$this"]
    val savedThisType = lambdaParamTypes["\$this"]
    if (receiverExpr != null) lambdaParamSubst["\$this"] = receiverExpr
    if (receiverType != null) lambdaParamTypes["\$this"] = receiverType

    // Bind each parameter: lambda params go into activeLambdas, value params become locals.
    // Two-pass approach: evaluate all argument expressions first, then declare parameter variables.
    // This prevents C self-initialization UB when a param name shadows an outer variable of the
    // same name (e.g. `rotr(x, n)` called with arg `x` would emit `ktc_Int x = x;` where the
    // right-hand `x` would refer to the newly declared uninitialized variable, not the outer one).
    data class BoundParam(val cTypeName: String, val paramName: String, val cVal: String, val scopeKtc: KtcType, val isNullable: Boolean)
    val vBoundParams = mutableListOf<BoundParam>()
    callArgs.forEachIndexed { i, arg ->
        val param = decl.params.getOrNull(i) ?: return@forEachIndexed
        val expr = arg.expr
        if (expr is LambdaExpr) {
            val funcParams = param.type.funcParams ?: emptyList()
            val paramTypes = funcParams.map { resolveTypeName(it).toInternalStr }
            val retType = param.type.funcReturn?.let { resolveTypeName(it).toInternalStr }
            newLambdas[param.name] = ActiveLambda(expr, paramTypes, retType)
        } else {
            val resolvedKtc = resolveTypeName(param.type)
            val isValueNullable = param.type.nullable && !param.type.annotations.any { it.name == "Ptr" }
            val (cTypeName, scopeKtc) = if (isValueNullable) {
                val innerKtc = resolveTypeName(param.type.copy(nullable = false))
                optCTypeName(innerKtc.toInternalStr) to KtcType.Nullable(innerKtc)
            } else {
                cTypeStr(resolvedKtc) to resolvedKtc
            }
            val cVal = genExpr(expr)
            flushPreStmts(ind)
            vBoundParams.add(BoundParam(cTypeName, param.name, cVal, scopeKtc, isValueNullable))
        }
    }
    for (vBp in vBoundParams) {
        if (vBp.isNullable) markOptional(vBp.paramName)
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
    }
    activeLambdas = newLambdas

    emitBlock(body, ind, method)

    impl.appendLine("$ind$labelName:;")
    activeLambdas = savedLambdas
    inlineReturnVar = savedRetVar
    inlineEndLabel = savedEndLabel
    if (receiverExpr != null) {
        if (savedThis != null) lambdaParamSubst["\$this"] = savedThis else lambdaParamSubst.remove("\$this")
    }
    if (receiverType != null) {
        if (savedThisType != null) lambdaParamTypes["\$this"] = savedThisType else lambdaParamTypes.remove("\$this")
    }
    popScope()
    // Smart cast propagation: if a nullable param was null-checked and bound to
    // a simple NameExpr, narrow the argument in the caller scope (e.g. checkNotNull(x)).
    val propCasts = mutableListOf<Pair<String, String>>()
    for ((i, arg) in callArgs.withIndex()) {
        val param = decl.params.getOrNull(i) ?: continue
        if (arg.expr !is NameExpr) continue
        val argName = arg.expr.name
        if (isMutable(argName)) continue  // var cannot be smart-cast
        val paramKtc = resolveTypeName(param.type.copy(nullable = false))
        val retKtc = decl.returnType?.let { resolveTypeName(it) }
        if (param.type.nullable && retKtc != null
            && retKtc.toInternalStr == paramKtc.toInternalStr) {
            defineVar(argName, retKtc.toInternalStr)
            propCasts.add(argName to retKtc.toInternalStr)
        }
    }
    impl.appendLine("$ind}")
    for ((name, narrowedType) in propCasts) {
        impl.appendLine("$ind// smart-cast: '$name' narrowed to '$narrowedType'")
    }
}

/* Expand a lambda call inside an inline body (statement position).
Lambda params are substituted via lambdaParamSubst rather than declared as C variables,
avoiding name-collision issues when lambda params shadow enclosing inline params. */
internal fun CCodeGen.emitLambdaCall(active: ActiveLambda, callArgs: List<Arg>, ind: String) {
    val savedSubst = lambdaParamSubst.toMap()
    val savedTypes = lambdaParamTypes.toMap()
    active.expr.params.forEachIndexed { i, pName ->
        val arg = callArgs.getOrNull(i)
        if (arg != null) {
            lambdaParamSubst[pName] = genExpr(arg.expr)
            // For ThisExpr args inside inline bodies, inferExprType returns null (no C $self scope);
            // fall back to lambdaParamTypes["\$this"] which was set by emitInlineCall's receiverType
            val t = (if (arg.expr is ThisExpr) lambdaParamTypes["\$this"] else null)
                ?: inferExprType(arg.expr)
                ?: active.paramTypes.getOrElse(i) { "" }
            if (t.isNotEmpty()) lambdaParamTypes[pName] = t
        }
    }
    for (stmt in active.expr.body) emitStmt(stmt, ind)
    lambdaParamSubst.clear(); lambdaParamSubst.putAll(savedSubst)
    lambdaParamTypes.clear(); lambdaParamTypes.putAll(savedTypes)
}
