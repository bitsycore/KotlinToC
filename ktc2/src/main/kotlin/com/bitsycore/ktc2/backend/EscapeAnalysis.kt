package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Finds the objects that never leave the block they are created in, and marks their
 * allocation to use the stack ([KAlloc.onStack]). Runs before `ArcInsert`.
 *
 * A reference *escapes* when it is returned, stored into a field, a global or an array
 * element, or passed to a parameter that escapes. Whether a parameter escapes is a summary
 * computed for every function to a fixpoint over the whole program; a dispatcher's summary is
 * the union of its targets', and a runtime function is assumed to keep what it is given.
 *
 * An allocation is moved to the stack when neither the local that receives it nor any local
 * it is copied to escapes, and all of those are declared inside the block of the allocation —
 * so nothing can still refer to the object when that block ends.
 */
class EscapeAnalysis(private val mModule: KModule) {

	/** Parameters and locals whose reference may outlive the function activation that holds them. */
	private val mEscaping = HashSet<KLocal>()

	fun run() {
		val vFunctions = mModule.units.flatMap { it.functions + listOfNotNull(it.initializer) }
		val vBodies = vFunctions.filter { !it.isExternal && it.dispatch == null }
		do {
			var vChanged = false
			for (vFunction in vFunctions) {
				if (vFunction.dispatch != null && summarizeDispatcher(vFunction)) vChanged = true
			}
			for (vFunction in vBodies) {
				if (FunctionScan(vFunction).markEscapes()) vChanged = true
			}
		} while (vChanged)
		vBodies.forEach { FunctionScan(it).promote() }
	}

	/** A parameter of a dispatcher escapes when it does in any implementation (or in the runtime default). */
	private fun summarizeDispatcher(inFunction: KFunction): Boolean {
		var vChanged = false
		inFunction.params.forEachIndexed { vIndex, vParam ->
			val vEscapes = inFunction.dispatchDefault != null ||
				inFunction.dispatch!!.any { (_, vTarget) -> vTarget.params.getOrNull(vIndex)?.let { it in mEscaping } != false }
			if (vEscapes && mEscaping.add(vParam)) vChanged = true
		}
		return vChanged
	}

	private fun parameterEscapes(inFunction: KFunction, inIndex: Int): Boolean =
		inFunction.isExternal || inFunction.params.getOrNull(inIndex)?.let { it in mEscaping } != false

	/** The locals whose reference [inValue] may be. */
	private fun sources(inValue: KExpr?, outLocals: MutableSet<KLocal>) {
		when (inValue) {
			is KRead -> outLocals += inValue.local
			is KRefCast -> sources(inValue.operand, outLocals)
			is KSelect -> {
				sources(inValue.whenTrue, outLocals)
				sources(inValue.whenFalse, outLocals)
			}
			is KRetain -> sources(inValue.operand, outLocals)
			else -> {}
		}
	}

	private fun sourcesOf(inValue: KExpr?): Set<KLocal> = HashSet<KLocal>().also { sources(inValue, it) }

	private inner class FunctionScan(private val mFunction: KFunction) {
		/** For each local, the locals its reference is copied to. */
		private val mFlows = HashMap<KLocal, MutableSet<KLocal>>()

		/** The block (identified by its statement list) each local is declared in, and each block's parent. */
		private val mBlockOf = HashMap<KLocal, List<KStmt>>()
		private val mParent = HashMap<List<KStmt>, List<KStmt>?>()
		private val mAllocations = ArrayList<Pair<KLocal, KAlloc>>()
		private var mChanged = false

		private fun escape(inValue: KExpr?) {
			for (vLocal in sourcesOf(inValue)) {
				if (mEscaping.add(vLocal)) mChanged = true
			}
		}

		private fun call(inValue: KExpr?) {
			when (inValue) {
				is KRefCast -> call(inValue.operand)
				is KCall -> inValue.arguments.forEachIndexed { vIndex, vArgument ->
					if (parameterEscapes(inValue.function, vIndex)) escape(vArgument)
				}
				is KIntrinsic -> if (!inValue.isPure) inValue.arguments.forEach { escape(it) }
				else -> {}
			}
		}

		private fun flow(inTarget: KLocal, inValue: KExpr?) {
			for (vSource in sourcesOf(inValue)) mFlows.getOrPut(vSource) { HashSet() } += inTarget
			call(inValue)
		}

		private fun scan(inBlock: List<KStmt>, inParentBlock: List<KStmt>?) {
			mParent[inBlock] = inParentBlock
			for (vStatement in inBlock) {
				when (vStatement) {
					is KLet -> {
						mBlockOf[vStatement.local] = inBlock
						flow(vStatement.local, vStatement.init)
						(vStatement.init as? KAlloc)?.let { mAllocations += vStatement.local to it }
					}
					is KSet -> flow(vStatement.local, vStatement.value)
					is KGlobalSet -> {
						escape(vStatement.value)
						call(vStatement.value)
					}
					is KFieldSet -> {
						escape(vStatement.value)
						call(vStatement.value)
					}
					is KIndexSet -> {
						escape(vStatement.value)
						call(vStatement.value)
					}
					is KEval -> call(vStatement.expr)
					is KReturn -> {
						escape(vStatement.value)
						call(vStatement.value)
					}
					is KIf -> {
						scan(vStatement.thenBlock, inBlock)
						scan(vStatement.elseBlock, inBlock)
					}
					is KLoop -> {
						scan(vStatement.body, inBlock)
						scan(vStatement.latch, inBlock)
					}
					is KConcat -> mBlockOf[vStatement.target] = inBlock
					is KPrint, is KBreak, is KContinue, is KRelease -> {}
				}
			}
		}

		/**
		 * Marks the locals of this function that escape, following copies between locals.
		 *
		 * @return true when a parameter or local was newly marked
		 */
		fun markEscapes(): Boolean {
			scan(mFunction.body, null)
			// A reference copied into a local that escapes escapes too.
			do {
				var vGrew = false
				for ((vSource, vTargets) in mFlows) {
					if (vSource !in mEscaping && vTargets.any { it in mEscaping }) {
						mEscaping += vSource
						vGrew = true
						mChanged = true
					}
				}
			} while (vGrew)
			return mChanged
		}

		private fun isInside(inBlock: List<KStmt>?, inAncestor: List<KStmt>): Boolean {
			var vBlock = inBlock
			while (vBlock != null) {
				if (vBlock === inAncestor) return true
				vBlock = mParent[vBlock]
			}
			return false
		}

		/** Every local the reference first held by [inLocal] can reach through copies. */
		private fun aliasesOf(inLocal: KLocal): Set<KLocal> {
			val vSeen = LinkedHashSet<KLocal>()
			val vPending = ArrayDeque(listOf(inLocal))
			while (vPending.isNotEmpty()) {
				val vNext = vPending.removeFirst()
				if (vSeen.add(vNext)) mFlows[vNext]?.let { vPending += it }
			}
			return vSeen
		}

		/** Moves to the stack the allocations whose object provably dies with its block. */
		fun promote() {
			scan(mFunction.body, null)
			for ((vLocal, vAllocation) in mAllocations) {
				val vBlock = mBlockOf[vLocal] ?: continue
				val vAliases = aliasesOf(vLocal)
				if (vAliases.any { it in mEscaping || !isInside(mBlockOf[it], vBlock) }) continue
				vAllocation.onStack = true
			}
		}
	}
}
