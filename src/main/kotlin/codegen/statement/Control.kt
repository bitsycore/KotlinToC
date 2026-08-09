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
		// Allow narrowing for trampoline Any, and for Ref<Any> → Ref<Concrete>
			if (ktc != null && ktc.toInternalStr != target) {
				val vExprType = ktc.stripNullable.toInternalStr
				val vResolvedTarget = resolveMonoNestedClass(target, vExprType)
				val vNarrowed: String? = when {
					// Ptr(Any) → value type (goes through .data deref in genName)
					ktc is KtcType.Ptr && ktc.inner is KtcType.Any -> vResolvedTarget
					// Nullable(Ptr(Any)) → pointer type (guard pattern, points to object)
					ktc is KtcType.Nullable && ktc.inner is KtcType.Ptr && ktc.inner.inner is KtcType.Any -> "${vResolvedTarget}*"
					ktc !is KtcType.Ptr -> vResolvedTarget
					else -> null
					}
			if (vNarrowed != null) casts.add(name to vNarrowed)
			}
		}
	fun tryThisCastTo(target: String) {
		val current = currentExtRecvType ?: lambdaParamTypes["\$this"] ?: return
		val castKey = if (currentExtRecvType != null) "\$self" else "\$this"
		if (current != target) casts.add(castKey to target)
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
		for ((name, type) in casts) {
			val vKtc = parseResolvedTypeName(type)
			val vExistingKtc = lookupVarKtc(name)
			val vExistingCName = lookupLocalVar(name)?.cName
			// For pointer narrows (Ref<Any> → Ref<Concrete>), emit a C cast
			val vCName = if (vKtc is KtcType.Ptr) "((${vKtc.toCType()})${lookupCName(name)})" else vExistingCName
			defineVar(name, LocalVar(ktc = vKtc, mutable = false, cName = vCName))
			}
	}

/** Pop the scope pushed by [pushSmartCasts]. No-op when [casts] is empty. */
internal fun CCodeGen.popSmartCasts(casts: List<Pair<String, String>>) {
	if (casts.isNotEmpty()) popScope()
	}

internal fun CCodeGen.emitIfStmt(e: IfExpr, ind: String, method: Boolean) {
	if (e.cond is BoolLit) {
		val v = e.cond.value
		codegenWarning("const-condition", "Condition is always ${if (v) "true" else "false"}")
		}
	if (isEmptyBlock(e.then)) codegenWarning("empty-body", "Empty 'if' body - has no effect.")
	if (e.els != null && isEmptyBlock(e.els)) codegenWarning("empty-body", "Empty 'else' body - has no effect.")
	if (e.els != null && !isEmptyBlock(e.then) && blocksMatch(e.then, e.els))
		codegenWarning("identical-branches", "Both 'if' and 'else' branches are identical - the condition has no effect.")
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

/* True if a block has no statements (or only comments - those don't run). */
internal fun isEmptyBlock(inBlock: Block): Boolean =
	inBlock.stmts.none { it !is CommentStmt }

/* Structural equality of two blocks, ignoring comments and the per-Stmt
   line/col metadata. Used to spot identical if/else branches. */
internal fun blocksMatch(inA: Block, inB: Block): Boolean {
	val vA = inA.stmts.filter { it !is CommentStmt }
	val vB = inB.stmts.filter { it !is CommentStmt }
	if (vA.size != vB.size) return false
	return vA.zip(vB).all { (a, b) -> a == b }
	}

// ── when (as statement) ──────────────────────────────────────────

internal fun CCodeGen.emitWhenStmt(e: WhenExpr, ind: String, method: Boolean) {
	val subjName = when (e.subject) {
		is NameExpr -> e.subject.name
		is ThisExpr -> "\$self"
		else        -> null
		}
	// Branches after the first 'else' are unreachable - the dispatch lowers to if/else if/else.
	val vElseIdx = e.branches.indexOfFirst { it.conds == null }
	if (vElseIdx in 0 until e.branches.size - 1)
		codegenError("Unreachable 'when' branch after 'else'")
	for ((bi, br) in e.branches.withIndex()) {
		if (isEmptyBlock(br.body))
			codegenWarning("empty-body", "Empty 'when' branch body - has no effect.")
		if (br.conds == null) {
			impl.appendLine("${ind}else {")
			} else {
			val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
			val keyword = if (bi == 0) "if" else "else if"
			impl.appendLine("$ind$keyword ($condStr) {")
			}
		val narrowCasts = if (br.conds != null && subjName != null && !isMutable(subjName)) {
			val isCond = br.conds.find { it is IsCond && !it.negated } as? IsCond
			if (isCond != null) {
				val vTarget = resolveTypeName(isCond.type).toInternalStr
				val vSubjKtc = inferExprTypeKtc(e.subject!!)
				val vResolved = resolveMonoNestedClass(vTarget, vSubjKtc.stripNullable?.toInternalStr ?: "")
				listOf(subjName to vResolved)
				}
			else emptyList()
			} else emptyList()
		pushSmartCasts(narrowCasts, ind)
		emitBlock(br.body, ind, method)
		popSmartCasts(narrowCasts)
		impl.appendLine("$ind}")
		}
	checkWhenExhaustiveness(e)
	}

internal fun CCodeGen.checkWhenExhaustiveness(e: WhenExpr) {
	if (e.subject == null) return
	if (e.branches.any { it.conds == null }) return
	val subjType = inferExprTypeKtc(e.subject) ?: return
	val vSubjName = subjType.stripNullable.toInternalStr

	// Sealed class / sealed interface exhaustiveness: each branch's IsCond
	// target narrows the subject to a concrete subclass. Cover all listed
	// subclasses and the `when` is exhaustive.
	val vIsSealed = classes[vSubjName]?.isSealed == true || interfaces[vSubjName]?.isSealed == true
	if (vIsSealed) {
		val vKnownSubs = sealedSubclasses[vSubjName] ?: emptyList()
		if (vKnownSubs.isNotEmpty()) {
			val vCovered = mutableSetOf<String>()
			for (br in e.branches) for (cond in br.conds ?: continue) {
				if (cond is IsCond && !cond.negated) vCovered += cond.type.name
				}
			val vMissing = vKnownSubs.filter { it !in vCovered }
			if (vMissing.isNotEmpty()) {
				val kind = if (classes[vSubjName]?.isSealed == true) "sealed class" else "sealed interface"
				codegenWarning("exhaustive-when",
					"'when' on $kind $vSubjName is not exhaustive; missing: ${vMissing.joinToString(", ")} - add an 'else' branch or handle all subclasses")
			}
		}
		return
	}

	val enumInfo = enumInfoFor(subjType) ?: enums[subjType.toInternalStr] ?: return
	val covered = mutableSetOf<String>()
	for (br in e.branches) {
		for (cond in br.conds ?: continue) {
			if (cond !is ExprCond) continue
			val entry = when (val expr = cond.expr) {
				is DotExpr -> expr.name
				is NameExpr -> expr.name
				else -> null
			}
			if (entry != null) covered += entry
		}
	}
	val missing = enumInfo.entries.filter { it !in covered }
	if (missing.isNotEmpty()) {
		val names = missing.joinToString(", ")
		codegenWarning("exhaustive-when", "'when' on enum ${enumInfo.name} is not exhaustive; missing: $names - add an 'else' branch or handle all entries")
	}
}

internal fun CCodeGen.genWhenCond(c: WhenCond, subject: Expr?): String {
	val subj = if (subject != null) genExpr(subject) else ""
	val vSubjEnum = if (subject != null) enumInfoFor(inferExprTypeKtc(subject).stripNullable) else null
	return when (c) {
		is ExprCond -> when {
			subject == null                                       -> genExpr(c.expr)
			vSubjEnum != null && !vSubjEnum.isSimple              -> "$subj.ordinal == ${genExpr(c.expr)}.ordinal"
			else                                                  -> "$subj == ${genExpr(c.expr)}"
			}
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
			val resolvedTarget = resolveMonoNestedClass(target, exprKtcCore?.toInternalStr ?: "")
			val memOp = if (exprKtcCore is KtcType.Ptr) "->" else "."
			val vTypeIdRef = typeIdExpr(exprKtcCore, subj, memOp)
			val check = if (classes.containsKey(target) || classes.containsKey(resolvedTarget)) {
				if (vTypeIdRef != null) "KTC_GET_TYPEID($vTypeIdRef) == ${typeFlatName(resolvedTarget)}_TYPE_ID"
				else "${typeFlatName((exprKtcCore as? KtcType.User)?.baseName ?: "")}_TYPE_ID == ${typeFlatName(resolvedTarget)}_TYPE_ID"
				} else if (interfaces.containsKey(target)) {
				val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
				if (impls.isEmpty()) "false"
				else if (vTypeIdRef != null) impls.joinToString(" || ") { "KTC_GET_TYPEID($vTypeIdRef) == ${typeFlatName(it)}_TYPE_ID" }
				else impls.joinToString(" || ") { "${typeFlatName((exprKtcCore as? KtcType.User)?.baseName ?: "")}_TYPE_ID == ${typeFlatName(it)}_TYPE_ID" }
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
					if (vTypeIdRef != null) "($vTypeIdRef == $typeId)"
					else "/* is-check: no typeId for ${exprKtcCore?.toInternalStr ?: "?"} */ false"
					}
				} else {
				"/* is ${c.type.name} */ true"
				}
			if (c.negated) "!($check)" else "($check)"
			}
		}
	}
