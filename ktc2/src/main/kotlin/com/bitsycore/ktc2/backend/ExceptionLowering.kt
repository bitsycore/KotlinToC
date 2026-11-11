package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Turns `throw` and `try` into checked propagation. Runs first, so the later passes only see
 * ordinary control flow.
 *
 * A thrown exception is parked in the runtime (`ktc2_throw`) and the function leaves at once:
 * to the end of the enclosing [KTryRegion] if there is one, otherwise by returning a zero
 * value. After every call to a function that may throw, the caller tests for a parked
 * exception and leaves the same way. Whether a function may throw is computed for the whole
 * program, so call chains that cannot throw carry no test at all.
 *
 * The failures the language itself raises — an index out of bounds, an integer division by
 * zero — are made explicit here too (unless the program is built with `--checks=abort`): the
 * test is placed in front of the statement and throws through the platform layer, and the
 * operation behind it is emitted unchecked. An operation that is only evaluated on one side
 * of a condition cannot be tested ahead; it keeps its check in the runtime, which parks the
 * exception, and the statement is followed by the usual test.
 *
 * Leaving is expressed with the constructs KIR already has — `break` out of a one-pass loop,
 * or `return` — so `ArcInsert` releases the locals of every scope that is left.
 */
class ExceptionLowering(private val mModule: KModule) {

	private val mMayThrow = HashSet<KFunction>()
	private var mNextTemp = 0

	/** True when index and division failures throw (the platform layer's throwers are in the module). */
	private val mChecksThrow = mModule.throwers.isNotEmpty()

	fun run() {
		val vFunctions = mModule.units.flatMap { it.functions + listOfNotNull(it.initializer) }
		do {
			var vChanged = false
			for (vFunction in vFunctions) {
				if (vFunction in mMayThrow) continue
				val vThrows = vFunction.dispatch?.any { it.second in mMayThrow } ?: throwsIn(vFunction.body)
				if (vThrows) {
					mMayThrow += vFunction
					vChanged = true
				}
			}
		} while (vChanged)

		for (vFunction in vFunctions) {
			if (vFunction.isExternal || vFunction.dispatch != null) continue
			val vBody = rewrite(vFunction.body, emptyList(), vFunction)
			vFunction.body.clear()
			vFunction.body += vBody
		}
	}

	/** True when [inFunction] can leave with a parked exception. */
	fun mayThrow(inFunction: KFunction): Boolean = inFunction in mMayThrow

	private fun callOf(inValue: KExpr?): KCall? = when (inValue) {
		is KCall -> inValue
		is KRefCast -> callOf(inValue.operand)
		else -> null
	}

	private fun callThrows(inValue: KExpr?): Boolean = callOf(inValue)?.function?.let { it in mMayThrow } == true

	/** The expression of [inStatement] that may be a call, if it has one. */
	private fun valueOf(inStatement: KStmt): KExpr? = when (inStatement) {
		is KLet -> inStatement.init
		is KSet -> inStatement.value
		is KGlobalSet -> inStatement.value
		is KFieldSet -> inStatement.value
		is KIndexSet -> inStatement.value
		is KEval -> inStatement.expr
		is KReturn -> inStatement.value
		else -> null
	}

	private fun hasCheck(inStatement: KStmt): Boolean =
		mChecksThrow && ((inStatement is KIndexSet && !inStatement.isChecked) || inStatement.ownExpressions().any { vRoot -> vRoot.anyNode { it.isRuntimeCheck() } })

	private fun throwsIn(inBlock: List<KStmt>): Boolean = inBlock.any { vStatement ->
		when (vStatement) {
			is KThrow -> true
			is KIf -> hasCheck(vStatement) || throwsIn(vStatement.thenBlock) || throwsIn(vStatement.elseBlock)
			is KLoop -> throwsIn(vStatement.body) || throwsIn(vStatement.latch)
			is KTryRegion -> throwsIn(vStatement.body)
			else -> callThrows(valueOf(vStatement)) || hasCheck(vStatement)
		}
	}

	private fun pending(): KExpr = KIntrinsic("ktc2_has_pending", emptyList(), KPrimType(KPrim.BOOLEAN))

	/** Leaves towards the handler: the end of the innermost try region, or the caller. */
	private fun unwind(inRegions: List<Int>, inFunction: KFunction): List<KStmt> {
		if (inRegions.isNotEmpty()) return listOf(KBreak(inRegions.last()))
		val vType = inFunction.returnType
		return listOf(KReturn(if (vType == KUnitType || vType == KNothingType) null else KZero(vType)))
	}

	// ============
	//  Language checks

	private fun thrower(inName: String, inArguments: List<KExpr>): KStmt = KEval(KCall(mModule.throwers.getValue(inName), inArguments))

	private fun indexGuard(inArray: KExpr, inIndex: KExpr, inLeave: List<KStmt>): KStmt = KIf(
		KIntrinsic("ktc2_index_bad", listOf(inArray, inIndex), KPrimType(KPrim.BOOLEAN)),
		listOf(thrower("throwIndexOutOfBounds", listOf(inIndex, KIntrinsic("ktc2_array_length", listOf(inArray), KPrimType(KPrim.INT))))) + inLeave,
		emptyList(),
	)

	/**
	 * Collects the tests for the checks in [inExpr] that are always evaluated, innermost first,
	 * and marks those operations as checked.
	 *
	 * @return true when [inExpr] also has a check that is evaluated conditionally, left to the runtime
	 */
	private fun guard(inExpr: KExpr, inIsConditional: Boolean, inLeave: List<KStmt>, outGuards: MutableList<KStmt>): Boolean {
		var vParks = false
		if (inExpr is KSelect) {
			vParks = guard(inExpr.condition, inIsConditional, inLeave, outGuards)
			if (guard(inExpr.whenTrue, true, inLeave, outGuards)) vParks = true
			if (guard(inExpr.whenFalse, true, inLeave, outGuards)) vParks = true
		} else {
			for (vChild in inExpr.children()) if (guard(vChild, inIsConditional, inLeave, outGuards)) vParks = true
		}
		if (!inExpr.isRuntimeCheck()) return vParks
		if (inIsConditional) return true
		when (inExpr) {
			is KIndexRead -> {
				outGuards += indexGuard(inExpr.array, inExpr.index, inLeave)
				inExpr.isChecked = true
			}
			is KBinary -> outGuards += KIf(
				KIntrinsic("ktc2_is_zero", listOf(inExpr.right), KPrimType(KPrim.BOOLEAN)),
				listOf(thrower("throwDivideByZero", emptyList())) + inLeave,
				emptyList(),
			)
			// Indexing a string is checked where the character is looked up.
			else -> return true
		}
		return vParks
	}

	// ============
	//  Rewriting

	private fun rewrite(inBlock: List<KStmt>, inRegions: List<Int>, inFunction: KFunction): List<KStmt> {
		val vOut = ArrayList<KStmt>()
		val vLeave = unwind(inRegions, inFunction)
		for (vStatement in inBlock) {
			var vParks = false
			if (mChecksThrow) {
				for (vRoot in vStatement.ownExpressions()) if (guard(vRoot, false, vLeave, vOut)) vParks = true
				if (vStatement is KIndexSet && !vStatement.isChecked) {
					vOut += indexGuard(vStatement.array, vStatement.index, vLeave)
					vStatement.isChecked = true
				}
			}
			when (vStatement) {
				is KThrow -> {
					vOut += KEval(KIntrinsic("ktc2_throw", listOf(vStatement.value), KUnitType, isPure = false))
					vOut += vLeave
				}
				// A region is a block that can be left from anywhere inside it: a loop that runs once.
				is KTryRegion -> vOut += KLoop(vStatement.id, rewrite(vStatement.body, inRegions + vStatement.id, inFunction) + KBreak(vStatement.id), emptyList())
				is KIf -> {
					val vThen = rewrite(vStatement.thenBlock, inRegions, inFunction)
					val vElse = rewrite(vStatement.elseBlock, inRegions, inFunction)
					if (vParks) {
						// The condition may have failed on the way: it is computed first, then looked at.
						val vCondition = KLocal("ktc2_x${mNextTemp++}", vStatement.condition.type)
						vOut += KLet(vCondition, vStatement.condition)
						vOut += KIf(pending(), vLeave, emptyList())
						vOut += KIf(KRead(vCondition), vThen, vElse)
					} else {
						vOut += KIf(vStatement.condition, vThen, vElse)
					}
				}
				is KLoop -> vOut += KLoop(vStatement.id, rewrite(vStatement.body, inRegions, inFunction), rewrite(vStatement.latch, inRegions, inFunction))
				is KReturn -> {
					if (!vParks && !callThrows(vStatement.value)) {
						vOut += vStatement
					} else if (vStatement.value!!.type == KUnitType || vStatement.value.type == KNothingType) {
						vOut += KEval(vStatement.value)
						vOut += KIf(pending(), vLeave, emptyList())
						vOut += KReturn(null)
					} else {
						// The result is only returned if computing it did not throw.
						val vResult = KLocal("ktc2_x${mNextTemp++}", vStatement.value.type)
						vOut += KLet(vResult, vStatement.value)
						vOut += KIf(pending(), vLeave, emptyList())
						vOut += KReturn(KRead(vResult))
					}
				}
				else -> {
					vOut += vStatement
					if (vParks || callThrows(valueOf(vStatement))) vOut += KIf(pending(), vLeave, emptyList())
				}
			}
		}
		return vOut
	}
}
