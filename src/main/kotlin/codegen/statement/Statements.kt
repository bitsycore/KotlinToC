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
	// Heap-closure target (Ref<(P)->R>) from a frame-bound functor: .asRef() would point at the stack
	// functor and dangle — the correct conversion is heap promotion via .copyWith(allocator).
	if (vDeclIsPtr && (vDeclCore as? KtcType.Ptr)?.inner is KtcType.Closure)
		codegenError("E070",
			"Cannot store a frame-bound closure in $inWhere (declared '$vDeclName'): a bare closure is " +
			"stack-bound and would dangle. Heap-promote it with '.copyWith(allocator)' (e.g. .copyWith(Heap)).")
	val vFix = if (vDeclIsPtr) ".asRef()" else ".refValue"
	codegenError("E070",
		"Ref↔value boundary for $inWhere: declared '$vDeclName' but initializer is '$vExprName'. " +
		"Use '$vFix' to make the conversion explicit."
	)
	}

/* True when [inExpr] denotes an lvalue — an existing storage location — rather than a freshly
produced rvalue. Binding or passing an lvalue of a user-value type is the implicit copy the
no-implicit-copy rule (E071) forbids; a CallExpr (constructor, function return, and crucially
.copy() / .copyWith()) is an rvalue and is always allowed through.

P0 scope: only the unambiguous lvalues — a bare local/param name and `this`. Field access
(DotExpr) and element access (IndexExpr) are deferred to a later phase: a DotExpr can be a
computed property (a getter produces a fresh rvalue, not storage), and `.refValue` is an
explicit Ref→value deref (the sanctioned boundary form, like .copy()) — both need to be
distinguished before the gate can safely cover them. */
internal fun isLValueExpr(inExpr: Expr): Boolean =
	when (inExpr) {
		is NameExpr -> true
		is ThisExpr -> true
		else        -> false  // CallExpr, DotExpr, IndexExpr, ObjectExpr, literals, … : deferred / rvalue
		}

/* E071 — reject an implicit copy of a value type: binding or passing a user class / data-class
lvalue into a by-value target. The only sanctioned copies are explicit .copy() (value copy → T) or
.copyWith(allocator) (heap copy → Ref<T>) — both CallExprs, so isLValueExpr lets them through. To
alias without copying, drop the type annotation (val x = src) or take a reference (src.asRef()). */
internal fun CCodeGen.checkImplicitCopy(
	inTargetKtc: KtcType?,
	inSrcExpr: Expr,
	inWhere: String,
	inAllowAlias: Boolean = true,   // false at a return site, where aliasing (&local) would dangle (E120)
	) {
	val vTarget = inTargetKtc.stripNullable ?: return     // nothing to guard when target type is unknown
	val vTargetSize = vTarget.asArr?.sized                // N for a @Size(N) array target (Arr or Ptr(Arr)), else null (P4)
	if (!vTarget.isUserValueType && vTargetSize == null) return   // guard only by-value class / data-class / @Size arrays
	// Generic copy-transparency: inside a generic instantiation, a value whose type is a type-parameter
	// substitution target (e.g. T → Vec2) is exempt — the generic author can't .copy() a T that may be a
	// primitive. Generic code copies T by value like a C++ template; the rule targets concrete user code.
	if (typeSubst.isNotEmpty() && typeSubst.values.any { it.removeSuffix("?") == vTarget.toInternalStr }) return
	if (!isLValueExpr(inSrcExpr)) return                  // rvalue (ctor / fn result / .copy()/.copyOf()) is fine
	val vSrc = inferExprTypeKtc(inSrcExpr).stripNullable ?: return  // source KtcType (stripped of Nullable)
	// Source must be a value lvalue: a user value, or — for a @Size(N) target — an array-like lvalue
	// (the @Size size is tracked side-band, not in the KtcType, so don't require vSrc to carry .sized).
	if (!vSrc.isUserValueType && !(vTargetSize != null && vSrc.isArrayLike)) return
	val vName = (inSrcExpr as? NameExpr)?.name ?: "value"          // best-effort name for the message
	// @Size(N) array (P4): a fixed-size stack struct — copy explicitly via .copyOf(N) (or .copy()). Gate
	// ONLY a genuine same-size @Size(N)→@Size(N) copy of a named lvalue. A different / unknown source size
	// is a truncating CONVERSION the implicit-.copyOf truncate warning already handles — leave it alone.
	if (vTargetSize != null) {
		val vSrcSize = (inSrcExpr as? NameExpr)?.name?.let { lookupArraySize(it) }
		if (vSrcSize != vTargetSize) return
		codegenError("E071",
			"Implicit copy of '$vName' into $inWhere (a @Size($vTargetSize) array — a fixed-size stack " +
			"struct). A copy must be explicit: use '$vName.copyOf($vTargetSize)' (or '$vName.copy()'). " +
			"To alias without copying, take a reference ($vName.asRef()).")
		}
	val vAlias = if (inAllowAlias)
		" To alias without copying, bind with no type annotation (val x = $vName) or take a reference ($vName.asRef())."
		else ""
	codegenError("E071",
		"Implicit copy of '$vName' into $inWhere (type '${vTarget.toInternalStr}'). A value copy " +
		"must be explicit: use '$vName.copy()' (value copy) or '$vName.copyWith(allocator)' (heap, " +
		"returns Ref<T>).$vAlias")
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
            if (loopDepth == 0) codegenError("E090", "'break' outside of a loop")
            // A break whose target loop encloses the innermost try would jump out of the
            // KTC_TRY for/do-while machinery without popping the exception frame.
            if (tryContexts.any { it.loopDepthAtEntry == loopDepth })
                codegenError("E132", "'break' would cross a 'try' boundary — the exception frame " +
                    "would stay armed. Restructure with a flag, or move the loop inside the try.")
            impl.appendLine("${ind}break;")
        }
        is ContinueStmt -> {
            if (loopDepth == 0) codegenError("E091", "'continue' outside of a loop")
            if (tryContexts.any { it.loopDepthAtEntry == loopDepth })
                codegenError("E132", "'continue' would cross a 'try' boundary — the exception frame " +
                    "would stay armed. Restructure with a flag, or move the loop inside the try.")
            impl.appendLine("${ind}continue;")
        }
        is DeferStmt -> deferStack.add(s.body)
        is TryStmt -> emitTry(s, ind, insideMethod)
        is ThrowStmt -> emitThrow(s, ind)
        is CommentStmt -> {
            impl.appendLine("$ind${s.text}")
        }
    }
    // Smart cast: if (x == null) return/break/continue → narrow x to non-null after
    applyGuardSmartCast(s)
}

/** After `if (x == null) ... <unconditional exit>` (no else), narrow x from T? to T.
   "Unconditional exit" = `return` / `break` / `continue`, or a call to a Nothing-returning
   function (`error()`, `TODO()`, `throw`-equivalent) — any of which guarantee control
   doesn't continue past the if.
   Only applies nullability narrowings here; `is` / `!is` narrowings interact poorly with
   the `as?` codegen on the narrowed variable, so they stay scoped to the if's THEN body. */
internal fun CCodeGen.applyGuardSmartCast(s: Stmt) {
    if (s !is ExprStmt) return
    val ifExpr = s.expr as? IfExpr ?: return
    if (ifExpr.els != null) return  // must have no else branch
    val lastStmt = ifExpr.then.stmts.lastOrNull() ?: return
    val vHardExit = lastStmt is ReturnStmt || lastStmt is BreakStmt || lastStmt is ContinueStmt || lastStmt is ThrowStmt
    val vNeverCall = lastStmt is ExprStmt && isNeverReturningCall(lastStmt.expr)
    if (!vHardExit && !vNeverCall) return

    val outerInd = currentInd.removeSuffix("    ")
    // For `return`/`break`/`continue`: apply BOTH null and type narrowings (legacy
    // behavior — code relies on `if (x !is T) return; x.method()`).
    // For `error()`/`TODO()`: only null narrowings are safe. Type narrowing through a
    // Nothing-call breaks the `as?` codegen (it doesn't see through the cast on a
    // value-type narrowed receiver), so we leave the variable unchanged for `is`.
    if (vHardExit) {
        for ((name, nonNullType) in extractSmartCasts(ifExpr.cond, forElse = true)) {
            impl.appendLine("${outerInd}// smart-cast: '$name' narrowed to '$nonNullType'")
            narrowVarType(name, nonNullType)
        }
    } else {
        forEachNullableNarrowing(ifExpr.cond) { name, nonNullType ->
            impl.appendLine("${outerInd}// smart-cast: '$name' narrowed to '$nonNullType'")
            narrowVarType(name, nonNullType)
        }
    }
}

/** Walk a guard condition and call [block] for every nullability narrowing that would
   hold AFTER the guard (i.e. the else-branch direction of the if). */
private fun CCodeGen.forEachNullableNarrowing(inCond: Expr, inBlock: (name: String, nonNullType: String) -> Unit) {
    when (inCond) {
        is BinExpr -> {
            if (inCond.op == "==") {
                // `x == null` in the THEN exits → in the continuation, x is non-null.
                val vName = when {
                    inCond.right is NullLit && inCond.left  is NameExpr -> inCond.left.name
                    inCond.left  is NullLit && inCond.right is NameExpr -> inCond.right.name
                    else -> null
                }
                if (vName != null && !isMutable(vName)) {
                    val vKtc = lookupVarKtc(vName)
                    if (vKtc is KtcType.Nullable) inBlock(vName, vKtc.inner.toInternalStr)
                }
            }
            // `||`: both branches must guarantee non-null on their own; treat the
            // common case where one operand is the null check by simple recursion.
            // No general handling beyond the direct `==` shape — anything fancier
            // stays out of the smart-cast pass for now.
        }
        else -> {}
    }
}

/** True if [inExpr] is a call to a function known to never return (`error`, `fatalError`, `TODO`). */
private fun isNeverReturningCall(inExpr: Expr): Boolean {
    if (inExpr !is CallExpr) return false
    val vCallee = inExpr.callee as? NameExpr ?: return false
    return vCallee.name == "error" || vCallee.name == "fatalError" || vCallee.name == "TODO"
}

internal fun CCodeGen.emitBlock(b: Block, ind: String, insideMethod: Boolean = false) {
    for ((idx, s) in b.stmts.withIndex()) {
        emitStmt(s, "$ind    ", insideMethod)
        // W024: unreachable code after unconditional exit. Warning, not error —
        // an early `return` is a useful debugging tool ("bisect this function by
        // returning at line N") and breaking the build there is too aggressive.
        if (s is ReturnStmt || s is BreakStmt || s is ContinueStmt || s is ThrowStmt) {
            val vRemaining = b.stmts.drop(idx + 1).filter { it !is CommentStmt }
            if (vRemaining.isNotEmpty()) {
                if (s.line > 0) { currentStmtLine = s.line; currentStmtCol = s.col }
                val vKw = when (s) { is ReturnStmt -> "return"; is BreakStmt -> "break"; is ThrowStmt -> "throw"; else -> "continue" }
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
                bindLambdaReturnTypeParams(inlineDecl, e.args, null, vSubst)
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
                val vSubst = inferInlineFunSubst(inlineExt, recvKtType, vArgTypes).toMutableMap()
                bindLambdaReturnTypeParams(inlineExt, e.args, recvKtType, vSubst)
                typeSubst = vSubst
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
    is ThrowExpr       -> true   // throws
    is IndexExpr       -> hasObservableEffect(inExpr.obj) || hasObservableEffect(inExpr.index)
    is DotExpr         -> hasObservableEffect(inExpr.obj)
    is SafeDotExpr     -> hasObservableEffect(inExpr.obj)
    is CastExpr        -> hasObservableEffect(inExpr.expr)
    is IsCheckExpr     -> hasObservableEffect(inExpr.expr)
    is ElvisExpr       -> hasObservableEffect(inExpr.left) || hasObservableEffect(inExpr.right)
    is IfExpr, is WhenExpr -> true   // statement-form already split off above
    else               -> false
}
