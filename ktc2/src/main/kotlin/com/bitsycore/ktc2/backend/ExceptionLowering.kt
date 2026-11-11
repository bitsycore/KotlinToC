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
 * Leaving is expressed with the constructs KIR already has — `break` out of a one-pass loop,
 * or `return` — so `ArcInsert` releases the locals of every scope that is left.
 */
class ExceptionLowering(private val mModule: KModule) {

	private val mMayThrow = HashSet<KFunction>()
	private var mNextTemp = 0

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

	private fun throwsIn(inBlock: List<KStmt>): Boolean = inBlock.any { vStatement ->
		when (vStatement) {
			is KThrow -> true
			is KIf -> throwsIn(vStatement.thenBlock) || throwsIn(vStatement.elseBlock)
			is KLoop -> throwsIn(vStatement.body) || throwsIn(vStatement.latch)
			is KTryRegion -> throwsIn(vStatement.body)
			else -> callThrows(valueOf(vStatement))
		}
	}

	private fun pending(): KExpr = KIntrinsic("ktc2_has_pending", emptyList(), KPrimType(KPrim.BOOLEAN))

	/** Leaves towards the handler: the end of the innermost try region, or the caller. */
	private fun unwind(inRegions: List<Int>, inFunction: KFunction): List<KStmt> {
		if (inRegions.isNotEmpty()) return listOf(KBreak(inRegions.last()))
		val vType = inFunction.returnType
		return listOf(KReturn(if (vType == KUnitType || vType == KNothingType) null else KZero(vType)))
	}

	private fun rewrite(inBlock: List<KStmt>, inRegions: List<Int>, inFunction: KFunction): List<KStmt> {
		val vOut = ArrayList<KStmt>()
		for (vStatement in inBlock) {
			when (vStatement) {
				is KThrow -> {
					vOut += KEval(KIntrinsic("ktc2_throw", listOf(vStatement.value), KUnitType, isPure = false))
					vOut += unwind(inRegions, inFunction)
				}
				// A region is a block that can be left from anywhere inside it: a loop that runs once.
				is KTryRegion -> vOut += KLoop(vStatement.id, rewrite(vStatement.body, inRegions + vStatement.id, inFunction) + KBreak(vStatement.id), emptyList())
				is KIf -> vOut += KIf(vStatement.condition, rewrite(vStatement.thenBlock, inRegions, inFunction), rewrite(vStatement.elseBlock, inRegions, inFunction))
				is KLoop -> vOut += KLoop(vStatement.id, rewrite(vStatement.body, inRegions, inFunction), rewrite(vStatement.latch, inRegions, inFunction))
				is KReturn -> {
					if (!callThrows(vStatement.value)) {
						vOut += vStatement
					} else if (vStatement.value!!.type == KUnitType || vStatement.value.type == KNothingType) {
						vOut += KEval(vStatement.value)
						vOut += KIf(pending(), unwind(inRegions, inFunction), emptyList())
						vOut += KReturn(null)
					} else {
						// The result is only returned if the call did not throw.
						val vResult = KLocal("ktc2_x${mNextTemp++}", vStatement.value.type)
						vOut += KLet(vResult, vStatement.value)
						vOut += KIf(pending(), unwind(inRegions, inFunction), emptyList())
						vOut += KReturn(KRead(vResult))
					}
				}
				else -> {
					vOut += vStatement
					if (callThrows(valueOf(vStatement))) vOut += KIf(pending(), unwind(inRegions, inFunction), emptyList())
				}
			}
		}
		return vOut
	}
}
