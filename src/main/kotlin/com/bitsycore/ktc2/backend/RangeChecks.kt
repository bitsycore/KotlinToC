package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Finds the array accesses whose index is known to be in range, and marks them as checked:
 * `ExceptionLowering` then puts no test in front of them and they are emitted as plain C
 * indexing, which the C compiler can vectorize. Runs before `ExceptionLowering`.
 *
 * What is recognized are the loops the importer writes. Going up, for `for (x in array)`,
 * `for (i in array.indices)`, `for (i in 0 until array.size)`, `Array(n) { }` and
 * `while (i < array.size) { …; i++ }`:
 *
 * ```
 * i = start
 * loop { if (i >= bound) break; body } latch { i = i + 1 }      // or the step in the body
 * ```
 *
 * where `i` is given no other value anywhere, `start` is a constant that is not negative, and
 * `bound` is the length of the array - read from it (`ktc2_array_length`), or the very value
 * the array was created with - or that length less a constant. In the body `start <= i <
 * bound` then holds, for `i` and for every local that is declared there as a copy of it (the
 * loop variable of the program), so `array[i + d]` is in range for every `d` from `-start`
 * to what the bound leaves below the length: `a[i + 1]` under `i < a.size - 1`, `a[i - 1]`
 * from `i = 1`. After a step that is in the body, `i` itself is one further: only its
 * earlier copies still count.
 *
 * Going down, for `for (i in array.size - 1 downTo 0)`:
 *
 * ```
 * i = length - c                                  // c at least 1
 * if (i >= 0) loop { body } latch { if (i == 0) break; i = i - 1 }
 * ```
 *
 * where `0 <= i <= length - c` holds in the body.
 *
 * Arrays never change their length, and only locals that are declared with their value and
 * never assigned again are followed, so the array and the bound are the same from the test to
 * the access.
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

		/**
		 * Looks for the loops of [inBlock] and the blocks inside it. [inNotNegative] are the
		 * locals this block is only entered with a value of zero or more (`if (i >= 0) { … }`).
		 */
		private fun loops(inBlock: List<KStmt>, inNotNegative: Set<KLocal> = emptySet()) {
			for (vStatement in inBlock) {
				when (vStatement) {
					is KIf -> {
						val vTest = vStatement.condition as? KBinary
						val vTested = (vTest?.left as? KRead)?.local?.takeIf {
							vTest.op == KBinaryOp.GE && vTest.operandType == KPrim.INT && ((vTest.right as? KConst)?.value as? Int) == 0
						}
						loops(vStatement.thenBlock, if (vTested != null) inNotNegative + vTested else inNotNegative)
						loops(vStatement.elseBlock, inNotNegative)
					}
					is KLoop -> {
						if (!upward(vStatement)) downward(vStatement, inNotNegative)
						loops(vStatement.body, inNotNegative)
						loops(vStatement.latch, inNotNegative)
					}
					is KTryRegion -> loops(vStatement.body, inNotNegative)
					else -> {}
				}
			}
		}

		/** `local + d` or `local - d` for a constant `d`, as the local and the signed offset; a plain read is offset 0. */
		private fun offsetOf(inIndex: KExpr): Pair<KLocal, Int>? {
			if (inIndex is KRead) return inIndex.local to 0
			val vSum = inIndex as? KBinary ?: return null
			if (vSum.operandType != KPrim.INT || (vSum.op != KBinaryOp.ADD && vSum.op != KBinaryOp.SUB)) return null
			val vLocal = (vSum.left as? KRead)?.local ?: return null
			val vBy = ((vSum.right as? KConst)?.value as? Int) ?: return null
			if (vBy < 0 || vBy > 1_000_000) return null
			return vLocal to if (vSum.op == KBinaryOp.ADD) vBy else -vBy
		}

		/**
		 * Marks the accesses `array[index + d]` in [inStatements] whose index is one of
		 * [inIndices] and whose offset `d` lies between [inLowest] and what [inSlack] gives for
		 * the array (the root of the local that holds it); null from [inSlack] says nothing is
		 * known of that array.
		 */
		private fun mark(inStatements: List<KStmt>, inIndices: Set<KLocal>, inLowest: Int, inSlack: (KLocal) -> Int?) {
			fun isProven(inArray: KExpr, inIndex: KExpr): Boolean {
				val (vIndex, vOffset) = offsetOf(inIndex) ?: return false
				if (vIndex !in inIndices) return false
				val vArray = fixedRoot(inArray) ?: return false
				val vSlack = inSlack(vArray) ?: return false
				return vOffset in inLowest..vSlack
			}
			inStatements.anyStatement { vStatement ->
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

		/** The locals of [inStatements] that are declared, once, as a copy of one of [inFrom]: with those. */
		private fun copiesOf(inFrom: Set<KLocal>, inStatements: List<KStmt>): Set<KLocal> {
			val vAll = HashSet(inFrom)
			do {
				var vGrew = false
				inStatements.anyStatement { vStatement ->
					if (vStatement is KLet && vStatement.local in mFixed && vStatement.local !in vAll) {
						val vSource = (vStatement.init as? KRead)?.local
						if (vSource != null && vSource in vAll && vAll.add(vStatement.local)) vGrew = true
					}
					false
				}
			} while (vGrew)
			return vAll
		}

		/** Marks the accesses in [inLoop] when it counts up as described above; false when it is no such loop. */
		private fun upward(inLoop: KLoop): Boolean {
			val vGuard = inLoop.body.firstOrNull() as? KIf ?: return false
			if (vGuard.elseBlock.isNotEmpty() || (vGuard.thenBlock.singleOrNull() as? KBreak)?.loopId != inLoop.id) return false
			val vTest = vGuard.condition as? KBinary ?: return false
			if (vTest.op != KBinaryOp.GE || vTest.operandType != KPrim.INT) return false
			val vCounter = (vTest.left as? KRead)?.local ?: return false
			// The counter starts at a constant that is not negative, and is only ever stepped by one: in
			// the latch, or by one statement of the body itself.
			if (mSets[vCounter] != 1 || vCounter in mFunction.params) return false
			val vStart = ((declaredValue(vCounter) as? KConst)?.value as? Int) ?: return false
			if (vStart < 0) return false
			val vInLatch = inLoop.latch.any { it is KSet && it.local === vCounter }
			val vStepAt = inLoop.body.indexOfFirst { it is KSet && it.local === vCounter }
			if (!vInLatch && vStepAt < 0) return false
			val vBefore = if (vInLatch) inLoop.body else inLoop.body.subList(0, vStepAt)
			val vIndices = copiesOf(setOf(vCounter), vBefore)
			val vStep = (if (vInLatch) inLoop.latch.first { it is KSet && it.local === vCounter } else inLoop.body[vStepAt]) as KSet
			val vSum = vStep.value as? KBinary ?: return false
			if (vSum.op != KBinaryOp.ADD || vSum.operandType != KPrim.INT || (vSum.left as? KRead)?.local !in vIndices || ((vSum.right as? KConst)?.value as? Int) != 1) return false

			val vSlack = boundOf(vTest.right) ?: return false
			mark(vBefore, vIndices, -vStart, vSlack)
			// From the step on the counter is one further; what was copied from it before is not.
			if (!vInLatch) mark(inLoop.body.subList(vStepAt, inLoop.body.size), vIndices - vCounter, -vStart, vSlack)
			return true
		}

		/** Marks the accesses in [inLoop] when it counts down to zero as described above. */
		private fun downward(inLoop: KLoop, inNotNegative: Set<KLocal>) {
			if (inLoop.latch.size != 2) return
			val vLeave = inLoop.latch[0] as? KIf ?: return
			if (vLeave.elseBlock.isNotEmpty() || (vLeave.thenBlock.singleOrNull() as? KBreak)?.loopId != inLoop.id) return
			val vTest = vLeave.condition as? KBinary ?: return
			if (vTest.op != KBinaryOp.EQ || vTest.operandType != KPrim.INT || ((vTest.right as? KConst)?.value as? Int) != 0) return
			val vCounter = (vTest.left as? KRead)?.local ?: return
			val vStep = inLoop.latch[1] as? KSet ?: return
			val vDifference = vStep.value as? KBinary ?: return
			if (vStep.local !== vCounter || vDifference.op != KBinaryOp.SUB || vDifference.operandType != KPrim.INT ||
				(vDifference.left as? KRead)?.local !== vCounter || ((vDifference.right as? KConst)?.value as? Int) != 1
			) return
			// Entered at zero or more, and left before it would go below.
			if (mSets[vCounter] != 1 || vCounter in mFunction.params || vCounter !in inNotNegative) return
			val (vArray, vBelow) = declaredValue(vCounter)?.let { lengthLess(it) } ?: return
			if (vBelow < 1) return
			mark(inLoop.body, copiesOf(setOf(vCounter), inLoop.body), 0) { vOf -> if (vOf === vArray) vBelow - 1 else null }
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
		 * For the bound a counter is tested against: what it leaves below the length of an array
		 * (given as the root of the local that holds it) - 0 when the bound is the length, `c`
		 * when it is the length less `c` - or null for an array the bound says nothing of. Null
		 * itself when the bound says nothing of any array.
		 */
		private fun boundOf(inBound: KExpr): ((KLocal) -> Int?)? {
			// A constant: the arrays created with at least that many elements.
			((inBound as? KConst)?.value as? Int)?.let { vCount ->
				return { vArray -> ((createdLength(vArray) as? KConst)?.value as? Int)?.let { it - vCount }?.takeIf { it >= 0 } }
			}
			// The length of an array, or that less a constant, written in the test itself or kept in a local.
			lengthLess(inBound)?.let { (vOf, vBelow) -> return { vArray -> if (vArray === vOf) vBelow else null } }
			val vLocal = fixedRoot(inBound) ?: return null
			val vKept = mFixed[vLocal]?.let { lengthLess(it) }
			return { vArray ->
				when {
					vKept != null && vArray === vKept.first -> vKept.second
					// It was created with the bound as its length.
					createdLength(vArray)?.let { fixedRoot(it) } === vLocal -> 0
					else -> null
				}
			}
		}

		/** `length(array)` or `length(array) - c` for a constant `c` that is not negative: the array (as the root of its local) and `c`. */
		private fun lengthLess(inValue: KExpr): Pair<KLocal, Int>? {
			lengthOperand(inValue)?.let { return it to 0 }
			val vDifference = inValue as? KBinary ?: return null
			if (vDifference.op != KBinaryOp.SUB || vDifference.operandType != KPrim.INT) return null
			val vBelow = ((vDifference.right as? KConst)?.value as? Int)?.takeIf { it in 0..1_000_000 } ?: return null
			return lengthOperand(vDifference.left)?.let { it to vBelow }
		}

		/** The array whose length [inValue] reads, as the root of its local; null when it is something else. */
		private fun lengthOperand(inValue: KExpr): KLocal? =
			(inValue as? KIntrinsic)?.takeIf { it.name == "ktc2_array_length" }?.arguments?.singleOrNull()?.let { fixedRoot(it) }

		/** The length the array held by the unchanging local [inArray] was created with, when it was created there. */
		private fun createdLength(inArray: KLocal): KExpr? = (mFixed[inArray]?.let { uncast(it) } as? KArrayNew)?.length
	}
}
