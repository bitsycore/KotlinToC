package com.bitsycore.ktc.codegen.statement

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.codegen.expression.genMethodCall
import com.bitsycore.ktc.codegen.expression.inferInlineFunSubst
import com.bitsycore.ktc.types.KtcType
import kotlin.math.abs

/* Crosses the Ref<T> ↔ T boundary check. Rejects an init/value whose KtcType
disagrees with the declared/target type on whether it's a reference. The user
must use .asRef() or .refValue explicitly. Skips array-element pointers (those
are unavoidable because Array<T> internally carries a T*), null literals
(NULL is a valid pointer value), interface receivers (their ktc_IfacePtr
wrap/unwrap is already explicit elsewhere), and initializers that are
themselves .asRef()/.refValue calls (already explicit at the syntax level). */
internal fun CCodeGen.checkPtrValueBoundary(
	inDeclTypeRef: TypeRef,
	inDeclKtc: KtcType,
	inExprKtc: KtcType?,
	inExpr: Expr,
	inWhere: String,
) {
	if (inExprKtc == null) return
	if (inExpr is NullLit) return
	// Already-explicit conversion: .asRef() or .refValue at the tail of the init expr.
	if (inExpr is CallExpr && inExpr.callee is DotExpr) {
		val vName = inExpr.callee.name
		if (vName == "asRef" || vName == "refValue") return
	}
	// Property-style .refValue access (not a call, just a DotExpr in expression position)
	if (inExpr is DotExpr && inExpr.name == "refValue") return
	val vDeclCore = inDeclKtc.stripNullable
	val vExprCore = inExprKtc.stripNullable
	val vDeclIsPtr = vDeclCore is KtcType.Ptr && vDeclCore.inner !is KtcType.Arr
	val vExprIsPtr = vExprCore is KtcType.Ptr && vExprCore.inner !is KtcType.Arr
	if (vDeclIsPtr == vExprIsPtr) return
	// Interface values (ktc_IfacePtr) carry a pointer internally but type as User —
	// allow them through the boundary without forcing .asRef()/.refValue at the syntax
	// level (the wrapping/unwrapping is already explicit in call/dispatch sites).
	if (vDeclCore is KtcType.User && interfaces.containsKey(vDeclCore.baseName)) return
	if (vExprCore is KtcType.User && interfaces.containsKey(vExprCore.baseName)) return
	val vDeclName = inDeclKtc.toInternalStr
	val vExprName = inExprKtc.toInternalStr
	val vFix = if (vDeclIsPtr) ".asRef()" else ".refValue"
	codegenError("E070",
		"Ref↔value boundary for $inWhere: declared '$vDeclName' but initializer is '$vExprName'. " +
		"Use '$vFix' to make the conversion explicit."
	)
	}

/* Statement dispatcher, block emitter and expression-statement emitter.
Inline/lambda expansion lives in Inline.kt.
Specialized handlers in other files:
  Var.kt     — var/val declarations
  Assign.kt  — assignment and return
  Print.kt   — print/println
  Control.kt — if/when with smart-casts
  For.kt     — for loops
  Inline.kt  — emitInlineCall, emitLambdaCall */

// ═══════════════════════════ Statements ═══════════════════════════

internal fun CCodeGen.emitStmt(s: Stmt, ind: String, insideMethod: Boolean = false) {
    if (s.line > 0) { currentStmtLine = s.line; currentStmtCol = s.col }
    currentInd = ind
    when (s) {
        is VarDeclStmt -> emitVarDecl(s, ind)
        is DestructuringDeclStmt -> emitDestructuringDecl(s, ind)
        is AssignStmt -> emitAssign(s, ind, insideMethod)
        is ReturnStmt -> emitReturn(s, ind)
        is ExprStmt -> emitExprStmt(s, ind, insideMethod)
        is ForStmt -> emitFor(s, ind, insideMethod)
        is WhileStmt -> {
            if (s.cond is BoolLit && !s.cond.value)
                codegenWarning("const-condition", "Condition is always false — 'while' body never runs.")
            if (isEmptyBlock(s.body))
                codegenWarning("empty-body", "Empty 'while' body — consider removing the loop.")
            loopDepth++
            impl.appendLine("${ind}while (${genExprFlushed(s.cond, ind)}) {")
            emitBlock(s.body, ind, insideMethod)
            impl.appendLine("$ind}")
            loopDepth--
        }

        is DoWhileStmt -> {
            if (s.cond is BoolLit && !s.cond.value)
                codegenWarning("const-condition", "Condition is always false — 'do-while' will run exactly once.")
            if (isEmptyBlock(s.body))
                codegenWarning("empty-body", "Empty 'do-while' body — consider removing the loop.")
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
    val casts = extractSmartCasts(ifExpr.cond, forElse = true)
    val outerInd = currentInd.removeSuffix("    ")
    for ((name, nonNullType) in casts) {
        impl.appendLine("${outerInd}// smart-cast: '$name' narrowed to '$nonNullType'")
        narrowVarType(name, nonNullType)
    }
}

internal fun CCodeGen.emitBlock(b: Block, ind: String, insideMethod: Boolean = false) {
    for ((idx, s) in b.stmts.withIndex()) {
        emitStmt(s, "$ind    ", insideMethod)
        // W024: unreachable code after unconditional exit. Warning, not error —
        // an early `return` is a useful debugging tool ("bisect this function by
        // returning at line N") and breaking the build there is too aggressive.
        if (s is ReturnStmt || s is BreakStmt || s is ContinueStmt) {
            val vRemaining = b.stmts.drop(idx + 1).filter { it !is CommentStmt }
            if (vRemaining.isNotEmpty()) {
                if (s.line > 0) { currentStmtLine = s.line; currentStmtCol = s.col }
                val vKw = when (s) { is ReturnStmt -> "return"; is BreakStmt -> "break"; else -> "continue" }
                codegenWarning("unreachable", "Unreachable code after '$vKw'.")
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
    // W018: allocator result dropped on the floor → guaranteed leak.
    // W033: pure expression with no side effect → almost always a typo.
    checkExprStmtForLeakOrNoEffect(e)
    // Inline function call — expand body at call site
    if (e is CallExpr && e.callee is NameExpr) {
        val name = e.callee.name
        val inlineCandidates = inlineFunDecls[name]
        val inlineDecl = when {
            inlineCandidates == null -> null
            inlineCandidates.size == 1 -> inlineCandidates[0]
            else -> {
                val exact = inlineCandidates.find { it.params.size == e.args.size }
                exact ?: inlineCandidates.minByOrNull { abs(it.params.size - e.args.size) }
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
        val recvObj = if (isSafe) e.callee.obj else (e.callee as DotExpr).obj
        val recvKtType = inferExprType(recvObj)?.removeSuffix("?")
        val inlineExt = findInlineExtFun(methodName, recvKtType, e.args.size)
        if (inlineExt != null) {
            val recvExpr = genExpr(recvObj)
            // Set up typeSubst for generic inline extension functions
            val vSavedSubst = typeSubst
            if (inlineExt.typeParams.isNotEmpty()) {
                val vArgTypes = e.args.map { inferExprType(it.expr) } // concrete arg types
                typeSubst = inferInlineFunSubst(inlineExt, recvKtType, vArgTypes)
            }
            if (isSafe) {
                // Safe call: guard the inline block with a null check
                val recvName = (recvObj as? NameExpr)?.name
                val recvType = if (recvName != null) lookupVar(recvName) else null
                val recvTypeKtc = if (recvType != null) parseResolvedTypeName(recvType) else null
                val guard = if (recvTypeKtc is KtcType.Nullable)
                    nullGuardExpr(recvTypeKtc, recvExpr, recvName ?: recvExpr, false)
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
    // Note: ref assignment goes through `p.refValue = x` (handled in emitAssign).
    // Safe method call as statement: a?.method() → if (guard) { method(a); }
    if (e is CallExpr && e.callee is SafeDotExpr) {
        val safe = e.callee
        val recvName = (safe.obj as? NameExpr)?.name
        val recvType = if (recvName != null) lookupVar(recvName) else null
        val recvTypeKtc = if (recvType != null) parseResolvedTypeName(recvType) else null
        if (recvType != null) {

            // Pointer-nullable (Heap<T>?, Ptr<T>?, Value<T>?, raw T*?) → NULL check
            val guard = if (recvTypeKtc is KtcType.Nullable && recvName != null)
                nullGuardExpr(recvTypeKtc, recvName, recvName, false)
            else null

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

private val kAllocatorMethods = setOf("allocWith", "copyWith", "resizeWith")

// W018 / W033: classify an expression-statement.
//   W018 fires when the expression is an allocator call whose result is dropped.
//   W033 fires when the expression has no observable effect (binary op, name, dot,
//   index, literal, !!, ?: on pure operands, etc. — anything that's not a call,
//   assign, or has a child with side effects).
internal fun CCodeGen.checkExprStmtForLeakOrNoEffect(inExpr: Expr) {
    val vRequireFree = requireFreeAllocatorCallName(inExpr)
    if (vRequireFree != null) {
        val (vMethod, vAllocName) = vRequireFree
        codegenWarning("discarded-alloc",
            "Result of '$vMethod($vAllocName)' is dropped — $vAllocName is @RequireFree, so " +
            "the allocation will leak. Bind it to a variable, return it, or free it explicitly.")
        return
    }
    if (!hasObservableEffect(inExpr)) {
        codegenWarning("no-effect-expr",
            "Expression statement has no observable effect — the value is computed and discarded.")
    }
}

// If [inExpr] is `<recv>.allocWith(A)` / `.copyWith(A)` / `.resizeWith(A, ...)` where A is
// an allocator marked @RequireFree, return (method, allocatorName); otherwise null.
// Arena-style allocators are intentionally exempt — they bulk-free on reset/dispose.
private fun CCodeGen.requireFreeAllocatorCallName(inExpr: Expr): Pair<String, String>? {
    if (inExpr !is CallExpr) return null
    val vCallee = inExpr.callee as? DotExpr ?: return null
    if (vCallee.name !in kAllocatorMethods) return null
    val vAllocArg = inExpr.args.firstOrNull()?.expr as? NameExpr ?: return null
    if (vAllocArg.name !in requireFreeAllocators) return null
    return vCallee.name to vAllocArg.name
}

// Conservative "does this expression have side effects?" check. Returns true for
// anything we can't prove pure: function calls, `!!` (may throw on null),
// nested expressions that contain a side-effect anywhere. Only literals, simple
// name/dot/index reads, and pure operators on pure operands are effect-free.
private fun hasObservableEffect(inExpr: Expr): Boolean = when (inExpr) {
    is CallExpr        -> true   // any call may have side effects
    is StrTemplateExpr -> inExpr.parts.any { p -> p is ExprPart && hasObservableEffect(p.expr) }
    is BinExpr         -> hasObservableEffect(inExpr.left) || hasObservableEffect(inExpr.right)
    is PrefixExpr      -> inExpr.op == "++" || inExpr.op == "--" || hasObservableEffect(inExpr.expr)
    is PostfixExpr     -> inExpr.op == "++" || inExpr.op == "--" || hasObservableEffect(inExpr.expr)
    is NotNullExpr     -> true   // !! may throw
    is IndexExpr       -> hasObservableEffect(inExpr.obj) || hasObservableEffect(inExpr.index)
    is DotExpr         -> hasObservableEffect(inExpr.obj)
    is SafeDotExpr     -> hasObservableEffect(inExpr.obj)
    is CastExpr        -> hasObservableEffect(inExpr.expr)
    is IsCheckExpr     -> hasObservableEffect(inExpr.expr)
    is ElvisExpr       -> hasObservableEffect(inExpr.left) || hasObservableEffect(inExpr.right)
    is IfExpr, is WhenExpr -> true   // statement-form already split off above
    else               -> false
}
