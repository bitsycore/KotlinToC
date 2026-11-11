package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/** Largest array (in elements) that is placed on the stack. */
private const val kStackArrayLimit = 64

/**
 * Finds the objects that never leave the block they are created in, and marks their
 * allocation to use the stack. Runs before `ArcInsert`.
 *
 * A reference *escapes* when it is returned, stored into a field, a global or an array
 * element, or passed to a parameter that escapes. Whether a parameter escapes is a summary
 * computed for every function to a fixpoint over the whole program; a dispatcher's summary is
 * the union of its targets', and a runtime function is assumed to keep what it is given.
 *
 * An allocation is moved to the stack when neither the local that receives it nor any local
 * it is copied to escapes, and all of those are declared inside the block of the allocation —
 * so nothing can still refer to the object when that block ends. This covers class instances
 * (closures and cells included), strings built by concatenation, and arrays of a small
 * constant size.
 */
class EscapeAnalysis(private val mModule: KModule) {

	/** Parameters and locals whose reference may outlive the block that holds them, with the first reason found. */
	private val mEscaping = HashMap<KLocal, String>()

	/** One line per allocation left on the heap: where, what, and why. Filled by [run]. */
	val heapReport = ArrayList<String>()

	/** Uses of inline storage that would need a copy the program has to ask for itself. Filled by [run]. */
	val errors = ArrayList<String>()

	private fun inlineRead(inValue: KExpr?): KFieldRead? = when (inValue) {
		is KFieldRead -> inValue.takeIf { it.field.isInline }
		is KRefCast -> inlineRead(inValue.operand)
		else -> null
	}

	/**
	 * A reference into inline storage has to be kept beyond its block: the value is copied to
	 * the heap. That is invisible for an immutable object; for a mutable one or an array the
	 * copy would be a different object than on the JVM, so it is refused.
	 */
	private fun copyOut(inRead: KFieldRead, inFunction: KFunction, inReason: String) {
		val vClass = (inRead.field.type as? KClassType)?.cls
		val vIsImmutable = vClass != null && generateSequence(vClass) { it.superClass }.all { vLevel -> vLevel.fields.all { it.isImmutable } }
		if (vIsImmutable) {
			inRead.needsCopy = true
		} else {
			val vWhat = if (vClass != null) "an object with 'var' properties" else "an array"
			errors += "${inFunction.cName}: inline field '${inRead.field.cName}' holds $vWhat and is $inReason; copy it explicitly (copy() / copyOf())"
		}
	}

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
			if (vEscapes && vParam !in mEscaping) {
				mEscaping[vParam] = "kept by an implementation"
				vChanged = true
			}
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

	/** A heap allocation that could move to the stack. */
	private class Candidate(val local: KLocal, val what: String, val origin: String?, val unfit: String?, val inline: KFieldRead? = null, val promote: () -> Unit)

	private inner class FunctionScan(private val mFunction: KFunction) {
		/** For each local, the locals its reference is copied to. */
		private val mFlows = HashMap<KLocal, MutableSet<KLocal>>()

		/** The block (identified by its statement list) each local is declared in, and each block's parent. */
		private val mBlockOf = HashMap<KLocal, List<KStmt>>()
		private val mParent = HashMap<List<KStmt>, List<KStmt>?>()
		private val mCandidates = ArrayList<Candidate>()
		private var mChanged = false

		private fun escape(inValue: KExpr?, inReason: String) {
			for (vLocal in sourcesOf(inValue)) {
				if (vLocal !in mEscaping) {
					mEscaping[vLocal] = inReason
					mChanged = true
				}
			}
		}

		private fun call(inValue: KExpr?) {
			when (inValue) {
				is KRefCast -> call(inValue.operand)
				is KCall -> inValue.arguments.forEachIndexed { vIndex, vArgument ->
					if (parameterEscapes(inValue.function, vIndex)) escape(vArgument, "passed to ${inValue.function.cName}, which keeps it")
				}
				is KIntrinsic -> if (!inValue.isPure) inValue.arguments.forEach { escape(it, "passed to ${inValue.name}") }
				else -> {}
			}
		}

		private fun flow(inTarget: KLocal, inValue: KExpr?) {
			for (vSource in sourcesOf(inValue)) mFlows.getOrPut(vSource) { HashSet() } += inTarget
			call(inValue)
		}

		private fun candidate(inLocal: KLocal, inValue: KExpr?) {
			when (inValue) {
				is KAlloc -> mCandidates += Candidate(inLocal, "a ${inValue.cls.name}", inValue.origin, null) { inValue.onStack = true }
				is KArrayNew -> {
					val vLength = (inValue.length as? KConst)?.value as? Int
					val vUnfit = if (vLength == null) "its size is not a constant" else if (vLength > kStackArrayLimit) "it has more than $kStackArrayLimit elements" else null
					mCandidates += Candidate(inLocal, "an array", inValue.origin, vUnfit) { inValue.onStack = true }
				}
				is KRefCast -> candidate(inLocal, inValue.operand)
				// A pointer into inline storage is a borrow that has to stay in its block, like a stack object.
				is KFieldRead -> if (inValue.field.isInline) mCandidates += Candidate(inLocal, "an inline value", null, null, inValue) {}
				else -> {}
			}
		}

		private fun scan(inBlock: List<KStmt>, inParentBlock: List<KStmt>?) {
			mParent[inBlock] = inParentBlock
			for (vStatement in inBlock) {
				when (vStatement) {
					is KLet -> {
						mBlockOf[vStatement.local] = inBlock
						flow(vStatement.local, vStatement.init)
						candidate(vStatement.local, vStatement.init)
					}
					is KSet -> flow(vStatement.local, vStatement.value)
					is KGlobalSet -> {
						escape(vStatement.value, "stored in a top-level property")
						call(vStatement.value)
					}
					is KFieldSet -> {
						// An inline field copies the content of the value: the reference goes nowhere.
						if (!vStatement.field.isInline) escape(vStatement.value, "stored in field '${vStatement.field.cName}'")
						call(vStatement.value)
					}
					is KIndexSet -> {
						escape(vStatement.value, "stored in an array")
						call(vStatement.value)
					}
					is KEval -> call(vStatement.expr)
					is KReturn -> {
						escape(vStatement.value, "returned")
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
					is KConcat -> {
						mBlockOf[vStatement.target] = inBlock
						mCandidates += Candidate(vStatement.target, "a string", vStatement.origin, null) { vStatement.onStack = true }
					}
					is KPrint, is KBreak, is KContinue, is KRelease -> {}
					is KThrow, is KTryRegion -> error("exceptions are lowered before this pass")
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
					val vVia = vTargets.firstOrNull { it in mEscaping }
					if (vSource !in mEscaping && vVia != null) {
						mEscaping[vSource] = mEscaping.getValue(vVia)
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

		/** Inline reads used directly as a value that is kept (returned by an accessor, for example). */
		private fun directInlineUses(inBlock: List<KStmt>) {
			for (vStatement in inBlock) {
				when (vStatement) {
					is KReturn -> inlineRead(vStatement.value)?.let { copyOut(it, mFunction, "returned") }
					is KGlobalSet -> inlineRead(vStatement.value)?.let { copyOut(it, mFunction, "stored in a top-level property") }
					is KFieldSet -> if (!vStatement.field.isInline) inlineRead(vStatement.value)?.let { copyOut(it, mFunction, "stored in another field") }
					is KIndexSet -> inlineRead(vStatement.value)?.let { copyOut(it, mFunction, "stored in an array") }
					is KIf -> {
						directInlineUses(vStatement.thenBlock)
						directInlineUses(vStatement.elseBlock)
					}
					is KLoop -> {
						directInlineUses(vStatement.body)
						directInlineUses(vStatement.latch)
					}
					else -> {}
				}
			}
		}

		/** Moves to the stack the allocations whose object provably dies with its block; reports the others. */
		fun promote() {
			scan(mFunction.body, null)
			directInlineUses(mFunction.body)
			for (vCandidate in mCandidates) {
				val vBlock = mBlockOf[vCandidate.local] ?: continue
				val vAliases = aliasesOf(vCandidate.local)
				val vReason = vCandidate.unfit
					?: vAliases.firstNotNullOfOrNull { mEscaping[it] }
					?: if (vAliases.any { !isInside(mBlockOf[it], vBlock) }) "kept in a variable of an enclosing block" else null
				if (vCandidate.inline != null) {
					if (vReason != null) copyOut(vCandidate.inline, mFunction, vReason)
				} else if (vReason == null) {
					vCandidate.promote()
				} else {
					heapReport += "${vCandidate.origin ?: mFunction.cName}: ${vCandidate.what} is allocated on the heap: $vReason"
				}
			}
		}
	}
}
