package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.emit.ifaceDataName
import com.bitsycore.ktc.types.KtcType

// ── if (as statement) ────────────────────────────────────────────

/**
 * Detect smart-cast candidates from a condition expression.
 * Returns a list of (varName, narrowedType) pairs for variables whose type is narrowed.
 * Handles value nullable ("T?") and pointer nullable ("T*?") via != null checks,
 * and type narrowing via `is T` / `this is T` checks.
 */
internal fun CCodeGen.extractSmartCasts(cond: Expr): List<Pair<String, String>> {
    val casts = mutableListOf<Pair<String, String>>()
    fun trySmartCast(name: String) {
        if (isMutable(name)) return  // var cannot be smart-cast
        val typeKtc = lookupVarKtc(name)
        if (typeKtc is KtcType.Nullable) {
            casts.add(name to typeKtc.inner.toInternalStr)
        }
    }

    fun tryThisSmartCast() {
        val type = currentExtRecvType
        if (type != null) {
            val typeKtc = parseResolvedTypeName(type)
            if (typeKtc is KtcType.Nullable)
                casts.add("\$self" to typeKtc.inner.toInternalStr)
        }
    }

    fun tryCastTo(name: String, targetType: String) {
        if (isMutable(name)) return
        val currentKtc = lookupVarKtc(name)
        // Don't narrow pointer types (Any* etc.) — they need original type for ->data dereference.
        // But DO narrow trampoline types (Any) — genName handles .data dereference after narrowing.
        if (currentKtc != null && currentKtc.toInternalStr != targetType
            && currentKtc !is KtcType.Ptr
        ) {
            casts.add(name to targetType)
        }
    }

    fun tryThisCastTo(targetType: String) {
        val currentType = currentExtRecvType ?: return
        if (currentType != targetType) {
            casts.add("\$self" to targetType)
        }
    }

    // x != null
    when (cond) {
        is BinExpr if cond.op == "!=" && cond.right is NullLit && cond.left is NameExpr ->
            trySmartCast(cond.left.name)
        // null != x
        is BinExpr if cond.op == "!=" && cond.left is NullLit && cond.right is NameExpr ->
            trySmartCast(cond.right.name)
        // this != null
        is BinExpr if cond.op == "!=" && cond.right is NullLit && cond.left is ThisExpr ->
            tryThisSmartCast()
        // null != this
        is BinExpr if cond.op == "!=" && cond.left is NullLit && cond.right is ThisExpr ->
            tryThisSmartCast()
        // x is Type
        is IsCheckExpr if !cond.negated && cond.expr is NameExpr ->
            tryCastTo(cond.expr.name, resolveTypeName(cond.type).toInternalStr)
        // this is Type
        is IsCheckExpr if !cond.negated && cond.expr is ThisExpr ->
            tryThisCastTo(resolveTypeName(cond.type).toInternalStr)
        // a && b → smart-cast both sides
        is BinExpr if cond.op == "&&" -> {
            casts.addAll(extractSmartCasts(cond.left))
            casts.addAll(extractSmartCasts(cond.right))
        }

        else -> {}
    }

    return casts
}

/** Detect smart-casts for the else branch (condition that proves null in the then branch, or !is in then branch). */
internal fun CCodeGen.extractElseSmartCasts(cond: Expr): List<Pair<String, String>> {
    val casts = mutableListOf<Pair<String, String>>()
    fun trySmartCast(name: String) {
        if (isMutable(name)) return
        val typeKtc = lookupVarKtc(name)
        if (typeKtc is KtcType.Nullable) {
            casts.add(name to typeKtc.inner.toInternalStr)
        }
    }

    fun tryCastTo(name: String, targetType: String) {
        if (isMutable(name)) return
        val currentKtc = lookupVarKtc(name)
        // Don't narrow pointer types
        if (currentKtc != null && currentKtc.toInternalStr != targetType
            && currentKtc !is KtcType.Ptr
        ) {
            casts.add(name to targetType)
        }
    }

    fun tryThisCastTo(targetType: String) {
        val currentType = currentExtRecvType ?: return
        if (currentType != targetType) {
            casts.add("\$self" to targetType)
        }
    }

    fun tryThisSmartCastElse() {
        val type = currentExtRecvType
        if (type != null) {
            val typeKtc = parseResolvedTypeName(type)
            if (typeKtc is KtcType.Nullable)
                casts.add("\$self" to typeKtc.inner.toInternalStr)
        }
    }

    // x == null → in else branch, x is non-null
    when (cond) {
        is BinExpr if cond.op == "==" && cond.right is NullLit && cond.left is NameExpr ->
            trySmartCast(cond.left.name)

        is BinExpr if cond.op == "==" && cond.left is NullLit && cond.right is NameExpr ->
            trySmartCast(cond.right.name)

        // this == null → in else branch, $self is non-null
        is BinExpr if cond.op == "==" && cond.right is NullLit && cond.left is ThisExpr ->
            tryThisSmartCastElse()

        // null == this → same
        is BinExpr if cond.op == "==" && cond.left is NullLit && cond.right is ThisExpr ->
            tryThisSmartCastElse()

        // x !is Type → in else branch, x IS Type
        is IsCheckExpr if cond.negated && cond.expr is NameExpr ->
            tryCastTo(cond.expr.name, resolveTypeName(cond.type).toInternalStr)

        // this !is Type → in else branch, $self IS Type
        is IsCheckExpr if cond.negated && cond.expr is ThisExpr ->
            tryThisCastTo(resolveTypeName(cond.type).toInternalStr)

        else -> {}
    }

    return casts
}

internal fun CCodeGen.emitIfStmt(e: IfExpr, ind: String, method: Boolean) {
    // Warn: constant boolean condition
    if (e.cond is BoolLit) {
        val vVal = e.cond.value
        codegenWarning("Condition is always ${if (vVal) "true" else "false"}")
    }
    impl.appendLine("${ind}if (${genExprFlushed(e.cond, ind)}) {")
    // Smart cast: narrow types in then-branch
    val thenCasts = extractSmartCasts(e.cond)
    if (thenCasts.isNotEmpty()) {
        for ((name, narrowedType) in thenCasts) {
            impl.appendLine("$ind    // smart-cast: '$name' narrowed to '$narrowedType'")
        }
        pushScope()
        for ((name, nonNullType) in thenCasts) defineVar(name, nonNullType)
    }
    emitBlock(e.then, ind, method)
    if (thenCasts.isNotEmpty()) popScope()

    if (e.els != null) {
        // check for else-if chain
        val single = e.els.stmts.singleOrNull()
        if (single is ExprStmt && single.expr is IfExpr) {
            impl.appendLine("$ind} else ")
            // Apply else-branch smart-casts before recursing (e.g. x == null → x non-null)
            val elseCasts = extractElseSmartCasts(e.cond)
            if (elseCasts.isNotEmpty()) {
                for ((name, narrowedType) in elseCasts) {
                    impl.appendLine("$ind    // smart-cast: '$name' narrowed to '$narrowedType'")
                }
                pushScope()
                for ((name, type) in elseCasts) defineVar(name, type)
            }
            emitIfStmt(single.expr, ind, method)
            if (elseCasts.isNotEmpty()) popScope()
            return
        }
        impl.appendLine("$ind} else {")
        // Smart cast: narrow nullable types in else-branch (x == null → else has x non-null)
        val elseCasts = extractElseSmartCasts(e.cond)
        if (elseCasts.isNotEmpty()) {
            for ((name, narrowedType) in elseCasts) {
                impl.appendLine("$ind    // smart-cast: '$name' narrowed to '$narrowedType'")
            }
            pushScope()
            for ((name, nonNullType) in elseCasts) defineVar(name, nonNullType)
        }
        emitBlock(e.els, ind, method)
        if (elseCasts.isNotEmpty()) popScope()
    }
    impl.appendLine("$ind}")
}

// ── when (as statement) ──────────────────────────────────────────

internal fun CCodeGen.emitWhenStmt(e: WhenExpr, ind: String, method: Boolean) {
    // ThisExpr subject maps to $self; NameExpr subject maps to its variable name
    val subjName = when (e.subject) {
        is NameExpr -> e.subject.name
        is ThisExpr -> "\$self"
        else -> null
    }
    for ((bi, br) in e.branches.withIndex()) {
        if (br.conds == null) {
            // else branch
            impl.appendLine("${ind}else {")
        } else {
            val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
            val keyword = if (bi == 0) "if" else "else if"
            impl.appendLine("$ind$keyword ($condStr) {")
        }
        // Smart cast: narrow subject type for `is` branches
        val vNarrowedKtc = if (br.conds != null && subjName != null && !isMutable(subjName)) {
            val isCond = br.conds.find { it is IsCond && !it.negated } as? IsCond
            if (isCond != null) resolveTypeName(isCond.type) else null
        } else null
        val narrowedType = vNarrowedKtc?.toInternalStr  // string form for comment
        if (vNarrowedKtc != null) {
            impl.appendLine("$ind    // smart-cast: '$subjName' narrowed to '$narrowedType'")
            pushScope()
            defineVarKtc(subjName!!, vNarrowedKtc)
        }
        emitBlock(br.body, ind, method)
        if (narrowedType != null) popScope()
        impl.appendLine("$ind}")
    }
}

internal fun CCodeGen.genWhenCond(c: WhenCond, subject: Expr?): String {
    val subj = if (subject != null) genExpr(subject) else ""
    return when (c) {
        is ExprCond -> if (subject != null) "$subj == ${genExpr(c.expr)}" else genExpr(c.expr)
        is InCond -> {
            val range = c.expr
            val neg = if (c.negated) "!" else ""
            if (range is BinExpr && range.op == "..") {
                "${neg}($subj >= ${genExpr(range.left)} && $subj <= ${genExpr(range.right)})"
            } else "${neg}(/* in ${genExpr(range)} */)"   // fallback
        }

        is IsCond -> {
            val targetKtc = resolveTypeName(c.type)
            val target = targetKtc.toInternalStr
            val exprKtc = if (subject != null) inferExprTypeKtc(subject) else null
            val exprKtcCore = (exprKtc as? KtcType.Nullable)?.inner ?: exprKtc
            val memOp = if (exprKtcCore is KtcType.Ptr) "->" else "."
            val check = if (classes.containsKey(target)) {
                "KTC_GET_TYPEID($subj${memOp}__base.typeId) == ${typeFlatName(target)}_TYPE_ID"
            } else if (interfaces.containsKey(target)) {
                val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
                if (impls.isEmpty()) "false"
                else impls.joinToString(" || ") { "KTC_GET_TYPEID($subj${memOp}__base.typeId) == ${typeFlatName(it)}_TYPE_ID" }
            } else if (targetKtc.isArrayLike) {
                if (exprKtcCore != null && exprKtcCore.isArrayLike) {
                    if (exprKtcCore.toInternalStr == target) "true" else "false"
                } else {
                    val arrayId = getTypeId(target)
                    "($subj${memOp}__array_type_id == $arrayId)"
                }
            } else if (targetKtc !is KtcType.User || targetKtc.kind != KtcType.UserKind.Class) {
                val isSourceNullable = exprKtc is KtcType.Nullable
                if (exprKtcCore != null && exprKtcCore !is KtcType.Ptr) {
                    if (exprKtcCore.toInternalStr == target) {
                        if (isSourceNullable && isValueNullableKtc(exprKtc)) "($subj.tag == ktc_SOME)"
                        else if (isSourceNullable) "($subj != NULL)"
                        else "true"
                    } else "false"
                } else {
                    val typeId = getTypeId(target)
                    "($subj${memOp}__base.typeId == $typeId)"
                }
            } else {
                "/* is ${c.type.name} */ true"
            }
            if (c.negated) "!($check)" else "($check)"
        }
    }
}
