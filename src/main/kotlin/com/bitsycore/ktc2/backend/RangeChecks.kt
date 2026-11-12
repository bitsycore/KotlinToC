package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Finds the array accesses whose index is known to be in range, and marks them as checked:
 * `ExceptionLowering` then puts no test in front of them and they are emitted as plain C
 * indexing, which the C compiler can vectorize. Runs before `ExceptionLowering`.
 *
 * What is recognized is the loop the importer writes for `for (x in array)`,
 * `for (i in array.indices)`, `for (i in 0 until array.size)` and `Array(n) { }`:
 *
 * ```
 * i = 0
 * loop { if (i >= bound) break; body } latch { i = i + 1 }
 * ```
 *
 * where `i` is given no other value anywhere, and `bound` is the length of the array: read
 * from it (`ktc2_array_length`), or the very value the array was created with. In the body
 * `0 <= i < length` then holds, for `i` and for every local that is declared there as a copy
 * of it (the loop variable of the program). Arrays never change their length, and only locals
 * that are declared with their value and never assigned again are followed, so the array and
 * the bound are the same from the test to the access.
 */
class RangeChecks(private val mModule: KModule) {

	/** Accesses left without a test. Filled by [run]. */
	var proven = 0

	fun run() {
		for (vUnit in mModule.units) {
			for (vFunction in vUnit.functions + listOfNotNull(vUnit.initializer)) {
				if (!vFunction.isExternal && vFunction.dispatch == null) FunctionScan(vFunction).run()
			}
		}
	}

	private inner class FunctionScan(private val mFunction: KFunction) {
		/** The value each local is declared with, for the locals that are given a value there and nowhere else. */
		private val mFixed = HashMap<KLocal, KExpr>()

		/** How many `KSet` each local receives. */
		private val mSets = HashMap<KLocal, Int>()

		fun run() {
			val vDeclared = HashMap<KLocal, KExpr>()
			mFunction.body.anyStatement { vStatement ->
				when (vStatement) {
					// A local declared in two places counts as assigned.
					is KLet -> if (vStatement.local in vDeclared) mSets.merge(vStatement.local, 1, Int::plus) else vStatement.init?.let { vDeclared[vStatement.local] = it }
					is KSet -> mSets.merge(vStatement.local, 1, Int::plus)
					is KConcat -> mSets.merge(vStatement.target, 1, Int::plus)
					else -> {}
				}
				false
			}
			for ((vLocal, vInit) in vDeclared) if (vLocal !in mSets) mFixed[vLocal] = vInit
			loops(mFunction.body)
		}

		private fun isFixed(inLocal: KLocal): Boolean = inLocal in mFixed || (inLocal in mFunction.params && inLocal !in mSets)

		private fun uncast(inValue: KExpr): KExpr = if (inValue is KRefCast) uncast(inValue.operand) else inValue

		/** The local [inLocal] is a copy of, through locals that never change; itself when it is no copy. */
		private fun rootOf(inLocal: KLocal): KLocal {
			var vLocal = inLocal
			while (true) {
				val vInit = mFixed[vLocal]?.let { uncast(it) } as? KRead ?: return vLocal
				if (!isFixed(vInit.local)) return vLocal
				vLocal = vInit.local
			}
		}

		/** The local read by [inValue] when it never changes, as its root; null otherwise. */
		private fun fixedRoot(inValue: KExpr): KLocal? =
			(uncast(inValue) as? KRead)?.local?.takeIf { isFixed(it) }?.let { rootOf(it) }

		private fun loops(inBlock: List<KStmt>) {
			for (vStatement in inBlock) {
				when (vStatement) {
					is KIf -> {
						loops(vStatement.thenBlock)
						loops(vStatement.elseBlock)
					}
					is KLoop -> {
						counted(vStatement)
						loops(vStatement.body)
						loops(vStatement.latch)
					}
					is KTryRegion -> loops(vStatement.body)
					else -> {}
				}
			}
		}

		/** Marks the accesses in the body of [inLoop] when it is a loop of the shape described above. */
		private fun counted(inLoop: KLoop) {
			val vGuard = inLoop.body.firstOrNull() as? KIf ?: return
			if (vGuard.elseBlock.isNotEmpty() || (vGuard.thenBlock.singleOrNull() as? KBreak)?.loopId != inLoop.id) return
			val vTest = vGuard.condition as? KBinary ?: return
			if (vTest.op != KBinaryOp.GE || vTest.operandType != KPrim.INT) return
			val vCounter = (vTest.left as? KRead)?.local ?: return
			// The counter starts at a constant that is not negative, and is only ever stepped by one in the latch of this loop.
			if (mSets[vCounter] != 1 || vCounter in mFunction.params) return
			val vStep = inLoop.latch.firstNotNullOfOrNull { vLatch -> (vLatch as? KSet)?.takeIf { it.local === vCounter } } ?: return
			val vSum = vStep.value as? KBinary ?: return
			if (vSum.op != KBinaryOp.ADD || (vSum.left as? KRead)?.local !== vCounter || ((vSum.right as? KConst)?.value as? Int) != 1) return
			val vStart = ((declaredValue(vCounter) as? KConst)?.value as? Int) ?: return
			if (vStart < 0) return

			val vBound = boundOf(vTest.right) ?: return
			// The locals of the body that hold the counter's value of the iteration.
			val vIndices = HashSet<KLocal>()
			vIndices += vCounter
			do {
				var vGrew = false
				inLoop.body.anyStatement { vStatement ->
					if (vStatement is KLet && vStatement.local in mFixed && vStatement.local !in vIndices) {
						val vSource = (vStatement.init as? KRead)?.local
						if (vSource != null && vSource in vIndices && vIndices.add(vStatement.local)) vGrew = true
					}
					false
				}
			} while (vGrew)

			fun isProven(inArray: KExpr, inIndex: KExpr): Boolean {
				val vIndex = (inIndex as? KRead)?.local ?: return false
				if (vIndex !in vIndices) return false
				val vArray = fixedRoot(inArray) ?: return false
				return vBound(vArray)
			}
			inLoop.body.anyStatement { vStatement ->
				if (vStatement is KIndexSet && !vStatement.isChecked && isProven(vStatement.array, vStatement.index)) {
					vStatement.isChecked = true
					proven++
				}
				for (vRoot in vStatement.ownExpressions()) {
					vRoot.anyNode { vNode ->
						if (vNode is KIndexRead && !vNode.isChecked && isProven(vNode.array, vNode.index)) {
							vNode.isChecked = true
							proven++
						}
						false
					}
				}
				false
			}
		}

		/** The value [inLocal] is declared with, whether or not it is assigned again. */
		private fun declaredValue(inLocal: KLocal): KExpr? {
			var vFound: KExpr? = null
			mFunction.body.anyStatement { vStatement ->
				if (vStatement is KLet && vStatement.local === inLocal) vFound = vStatement.init
				vFound != null
			}
			return vFound
		}

		/**
		 * For the bound a counter is tested against: the test that tells whether an array (given
		 * as the root of the local that holds it) is at least that long. Null when the bound says
		 * nothing of any array.
		 */
		private fun boundOf(inBound: KExpr): ((KLocal) -> Boolean)? {
			// A constant: the arrays created with at least that many elements.
			((inBound as? KConst)?.value as? Int)?.let { vCount ->
				return { vArray -> ((createdLength(vArray) as? KConst)?.value as? Int)?.let { it >= vCount } == true }
			}
			// The length of an array, read in the test itself or kept in a local.
			lengthOperand(inBound)?.let { vOf -> return { vArray -> vArray === vOf } }
			val vLocal = fixedRoot(inBound) ?: return null
			val vOf = mFixed[vLocal]?.let { lengthOperand(it) }
			return { vArray ->
				// Its length was read into the bound, or it was created with the bound as its length.
				vArray === vOf || createdLength(vArray)?.let { fixedRoot(it) } === vLocal
			}
		}

		/** The array whose length [inValue] reads, as the root of its local; null when it is something else. */
		private fun lengthOperand(inValue: KExpr): KLocal? =
			(inValue as? KIntrinsic)?.takeIf { it.name == "ktc2_array_length" }?.arguments?.singleOrNull()?.let { fixedRoot(it) }

		/** The length the array held by the unchanging local [inArray] was created with, when it was created there. */
		private fun createdLength(inArray: KLocal): KExpr? = (mFixed[inArray]?.let { uncast(it) } as? KArrayNew)?.length
	}
}
