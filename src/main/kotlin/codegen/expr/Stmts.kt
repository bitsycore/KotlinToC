package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

/* Statement dispatcher, block emitter and expression-statement emitter.
Inline/lambda expansion lives in StmtsInline.kt.
Specialized handlers in other files:
  StmtsVar.kt     — var/val declarations
  StmtsAssign.kt  — assignment and return
  StmtsPrint.kt   — print/println
  StmtsControl.kt — if/when with smart-casts
  StmtsFor.kt     — for loops
  StmtsInline.kt  — emitInlineCall, emitLambdaCall */

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
