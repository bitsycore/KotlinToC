package com.bitsycore.ktc.codegen.statement

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.types.KtcType

// ── if (as statement) ────────────────────────────────────────────

/**
 * Extract smart-cast candidates from a condition expression.
 *
 * When [forElse] is false (default) returns narrowings that hold in the **then** branch
 * (e.g. `x != null` → x is non-null, `x is T` → x is T).
 * When [forElse] is true returns narrowings that hold in the **else** branch
 * (e.g. `x == null` → x non-null in else, `x !is T` → x is T in else).
 */
internal fun CCodeGen.extractSmartCasts(cond: Expr, forElse: Boolean = false): List<Pair<String, String>> {
	val casts = mutableListOf<Pair<String, String>>()

	fun tryNullNarrow(name: String) {
		if (isMutable(name)) return
		val ktc = lookupVarKtc(name)
		if (ktc is KtcType.Nullable) casts.add(name to ktc.inner.toInternalStr)
		}
	fun tryThisNullNarrow() {
		val type = currentExtRecvType ?: return
		val ktc  = parseResolvedTypeName(type)
		if (ktc is KtcType.Nullable) casts.add("\$self" to ktc.inner.toInternalStr)
		}
	fun tryCastTo(name: String, target: String) {
		if (isMutable(name)) return
		val ktc = lookupVarKtc(name)
		// Don't narrow pointer types (Any* etc.) — they need original type for ->data dereference.
		// DO narrow trampoline types (Any) — genName handles .data dereference after narrowing.
		if (ktc != null && ktc.toInternalStr != target && ktc !is KtcType.Ptr)
			casts.add(name to target)
		}
	fun tryThisCastTo(target: String) {
		val current = currentExtRecvType ?: return
		if (current != target) casts.add("\$self" to target)
		}

	val nullOp = if (forElse) "==" else "!="  // null check op for this branch direction
	val isNeg  = forElse                        // `is` is negated for else-branch detection

	when (cond) {
		is BinExpr if cond.op == nullOp && cond.right is NullLit && cond.left  is NameExpr -> tryNullNarrow(cond.left.name)
		is BinExpr if cond.op == nullOp && cond.left  is NullLit && cond.right is NameExpr -> tryNullNarrow(cond.right.name)
		is BinExpr if cond.op == nullOp && cond.right is NullLit && cond.left  is ThisExpr -> tryThisNullNarrow()
		is BinExpr if cond.op == nullOp && cond.left  is NullLit && cond.right is ThisExpr -> tryThisNullNarrow()
		is IsCheckExpr if cond.negated == isNeg && cond.expr is NameExpr -> tryCastTo(cond.expr.name, resolveTypeName(cond.type).toInternalStr)
		is IsCheckExpr if cond.negated == isNeg && cond.expr is ThisExpr -> tryThisCastTo(resolveTypeName(cond.type).toInternalStr)
		is BinExpr if !forElse && cond.op == "&&" -> {
			casts.addAll(extractSmartCasts(cond.left))
			casts.addAll(extractSmartCasts(cond.right))
			}
		else -> {}
		}
	return casts
	}

/**
 * Push a smart-cast scope: emit comment lines into [impl] and enter a new variable scope.
 * Pair with [popSmartCasts] using the same [casts] list.
 * No-op when [casts] is empty.
 */
internal fun CCodeGen.pushSmartCasts(casts: List<Pair<String, String>>, ind: String) {
	if (casts.isEmpty()) return
	for ((name, type) in casts) impl.appendLine("$ind    // smart-cast: '$name' narrowed to '$type'")
	pushScope()
	for ((name, type) in casts) defineVar(name, type)
	}

/** Pop the scope pushed by [pushSmartCasts]. No-op when [casts] is empty. */
internal fun CCodeGen.popSmartCasts(casts: List<Pair<String, String>>) {
	if (casts.isNotEmpty()) popScope()
	}

internal fun CCodeGen.emitIfStmt(e: IfExpr, ind: String, method: Boolean) {
	if (e.cond is BoolLit) {
		val v = e.cond.value
		codegenWarning("Condition is always ${if (v) "true" else "false"}")
		}
	impl.appendLine("${ind}if (${genExprFlushed(e.cond, ind)}) {")
	val thenCasts = extractSmartCasts(e.cond)
	pushSmartCasts(thenCasts, ind)
	emitBlock(e.then, ind, method)
	popSmartCasts(thenCasts)

	if (e.els != null) {
		val elseCasts = extractSmartCasts(e.cond, forElse = true)
		val single    = e.els.stmts.singleOrNull()
		if (single is ExprStmt && single.expr is IfExpr) {
			impl.appendLine("$ind} else ")
			pushSmartCasts(elseCasts, ind)
			emitIfStmt(single.expr, ind, method)
			popSmartCasts(elseCasts)
			return
			}
		impl.appendLine("$ind} else {")
		pushSmartCasts(elseCasts, ind)
		emitBlock(e.els, ind, method)
		popSmartCasts(elseCasts)
		}
	impl.appendLine("$ind}")
	}

// ── when (as statement) ──────────────────────────────────────────

internal fun CCodeGen.emitWhenStmt(e: WhenExpr, ind: String, method: Boolean) {
	val subjName = when (e.subject) {
		is NameExpr -> e.subject.name
		is ThisExpr -> "\$self"
		else        -> null
		}
	for ((bi, br) in e.branches.withIndex()) {
		if (br.conds == null) {
			impl.appendLine("${ind}else {")
			} else {
			val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
			val keyword = if (bi == 0) "if" else "else if"
			impl.appendLine("$ind$keyword ($condStr) {")
			}
		val narrowCasts = if (br.conds != null && subjName != null && !isMutable(subjName)) {
			val isCond = br.conds.find { it is IsCond && !it.negated } as? IsCond
			if (isCond != null) listOf(subjName to resolveTypeName(isCond.type).toInternalStr)
			else emptyList()
			} else emptyList()
		pushSmartCasts(narrowCasts, ind)
		emitBlock(br.body, ind, method)
		popSmartCasts(narrowCasts)
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
			val exprKtcCore = exprKtc.stripNullable
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
