package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Shares the releases of the ways out of a function.
 *
 * `ArcInsert` writes in front of each `return` the releases of everything still held, and after each
 * call that can throw comes such a way out: a function that holds n objects and calls n times has some
 * n * n / 2 releases, which is most of the C of a long initializer (a `mapOf` of hundreds of pairs) or
 * of a function with many temporaries. Here each way out that returns a constant (the zero of the
 * function's type after a failed call, or nothing) goes to a ladder instead: the releases written once,
 * the last object made first, each step falling to the next, down to the `return`.
 *
 * ```c
 * a = make(); if (failed) goto out_1;       out_3: release(c);
 * b = make(); if (failed) goto out_2;       out_2: release(b);
 * c = make(); if (failed) goto out_3;       out_1: release(a);
 *                                           out_0: return 0;
 * ```
 *
 * The ways out are put together as a tree read from the `return` back: two that release the same
 * objects last share those steps. A step stands at the end of the block that declares its object, so
 * that the object (and the frame slot it may be in) is still there when it is released: the steps of an
 * inner block come first, at its end, and go on to the outer block's. A jump only ever goes forward, or
 * out of a block, never into one. Left as they are: coroutines (their ways out are taken apart by
 * `CEmitter`), and the `else` of a chain of tests, which `CEmitter.flatChain` writes at one level.
 */
class ReleaseLadders(private val mModule: KModule) {
	/** How many ways out were made jumps, and how many releases were written fewer: for `KTC2_DEBUG_PASSES`. */
	var exits = 0
		private set
	var saved = 0
		private set

	fun run() {
		for (vUnit in mModule.units) {
			for (vFunction in vUnit.functions.toList() + listOfNotNull(vUnit.initializer)) {
				if (vFunction.isExternal || vFunction.dispatch != null || vFunction.coroutine != null || vFunction.body.isEmpty()) continue
				val vBody = Ladders(vFunction).rewrite() ?: continue
				vFunction.body.clear()
				vFunction.body += vBody
			}
		}
	}

	/** A block of statements of the function: its statements' list, the block it is inside, and whether it may hold steps. */
	private class Block(val id: Int, val parent: Block?, val allowsSteps: Boolean) {
		fun isInside(inOther: Block): Boolean {
			var vBlock: Block? = this
			while (vBlock != null) {
				if (vBlock === inOther) return true
				vBlock = vBlock.parent
			}
			return false
		}
	}

	/** A step of a ladder: the release of [local] (none for the `return` it ends with), then [parent]. */
	private class Step(val local: KLocal?, val parent: Step?, val home: Block, val returned: KReturn?) {
		val children = LinkedHashMap<KLocal, Step>()
		var label = ""
	}

	/** A way out: the statements it is made of ([from] to the `return`), what it releases first itself, and its step. */
	private class Exit(val from: Int, val inline: List<KStmt>, val step: Step)

	private inner class Ladders(private val mFunction: KFunction) {
		private val mBlocks = ArrayList<Block>()
		private val mHomes = HashMap<KLocal, Block>()
		private val mRoots = LinkedHashMap<String, Step>()

		/** The ways out of each list of statements, by the block's id: where each starts. */
		private val mExits = HashMap<Int, MutableMap<Int, Exit>>()
		private var mNextLabel = 0

		fun rewrite(): List<KStmt>? {
			val vTop = Block(0, null, true).also { mBlocks += it }
			for (vParameter in mFunction.params) mHomes[vParameter] = vTop
			homes(mFunction.body, vTop)
			mNextBlock = 1
			find(mFunction.body, vTop)
			if (mExits.isEmpty()) return null
			// A step that more than one way goes to, or that is not reached by falling from the one before it, needs its label.
			mRoots.values.forEach { label(it) }
			orders()
			mNextBlock = 1
			return rewritten(mFunction.body, vTop)
		}

		private var mNextBlock = 1

		/** Where each local is declared, and the blocks, in the order [find] and [rewritten] go through them. */
		private fun homes(inStatements: List<KStmt>, inBlock: Block) {
			for (vStatement in inStatements) {
				when (vStatement) {
					is KLet -> mHomes.putIfAbsent(vStatement.local, inBlock)
					is KConcat -> mHomes.putIfAbsent(vStatement.target, inBlock)
					is KIf -> {
						homes(vStatement.thenBlock, newBlock(inBlock, true))
						// The `else` of a chain of tests is written at the level of the chain (`CEmitter.flatChain`).
						homes(vStatement.elseBlock, newBlock(inBlock, vStatement.elseBlock.lastOrNull() !is KIf))
					}
					is KLoop -> {
						val vBody = newBlock(inBlock, true)
						homes(vStatement.body, vBody)
						// Body and latch are one block of C: what the body declares, the latch sees.
						homes(vStatement.latch, newBlock(vBody, true))
					}
					else -> {}
				}
			}
		}

		private fun newBlock(inParent: Block, inAllowsSteps: Boolean): Block = Block(mBlocks.size, inParent, inAllowsSteps).also { mBlocks += it }

		/** The next block, in the order [homes] made them. */
		private fun nextBlock(): Block = mBlocks[mNextBlock++]

		/** Finds the ways out of [inStatements] and of the blocks inside, and puts them in the ladders. */
		private fun find(inStatements: List<KStmt>, inBlock: Block) {
			for ((vIndex, vStatement) in inStatements.withIndex()) {
				when (vStatement) {
					is KIf -> {
						find(vStatement.thenBlock, nextBlock())
						find(vStatement.elseBlock, nextBlock())
					}
					is KLoop -> {
						find(vStatement.body, nextBlock())
						find(vStatement.latch, nextBlock())
					}
					is KReturn -> exit(inStatements, vIndex, inBlock)?.let { mExits.getOrPut(inBlock.id) { HashMap() }[it.from] = it }
					else -> {}
				}
			}
		}

		/**
		 * The way out that ends with the `return` at [inAt] of [inStatements], when it is one: the releases of locals
		 * before it, and a constant returned (or nothing). Null otherwise.
		 */
		private fun exit(inStatements: List<KStmt>, inAt: Int, inBlock: Block): Exit? {
			val vReturn = inStatements[inAt] as KReturn
			var vFrom = inAt
			val vReleased = ArrayList<KLocal>()
			while (vFrom > 0) {
				val vLocal = ((inStatements[vFrom - 1] as? KRelease)?.operand as? KRead)?.local ?: break
				vReleased.add(0, vLocal)
				vFrom--
			}
			if (vReleased.isEmpty()) return null
			// The value: none, a constant, or a local given a constant just before the releases (computed before them).
			var vValue = vReturn.value
			val vRead = (vValue as? KRead)?.local
			if (vRead != null) {
				val vLet = inStatements.getOrNull(vFrom - 1) as? KLet
				if (vLet?.local !== vRead || vRead in vReleased) return null
				vValue = vLet.init
				vFrom--
			}
			val vKey = when (vValue) {
				null -> "nothing"
				is KZero -> "zero"
				is KNull -> "null"
				is KConst -> "const ${vValue.type} ${vValue.value}"
				else -> return null
			}
			// The releases go from the innermost block out: those in blocks that cannot hold steps stay where they are,
			// the others become steps, each at the end of the block that declares its local.
			val vHomes = vReleased.map { mHomes[it] ?: return null }
			if (vHomes.any { !inBlock.isInside(it) }) return null
			if (vHomes.zipWithNext().any { (vInner, vOuter) -> !vInner.isInside(vOuter) }) return null
			val vKept = vHomes.indexOfFirst { it.allowsSteps }
			if (vKept < 0) return null
			if (vHomes.drop(vKept).any { !it.allowsSteps }) return null
			val vTop = mBlocks[0]
			val vRoot = mRoots.getOrPut(vKey) { Step(null, null, vTop, KReturn(vValue)) }
			var vStep = vRoot
			for (vIndex in vReleased.indices.reversed().filter { it >= vKept }) {
				val vLocal = vReleased[vIndex]
				vStep = vStep.children.getOrPut(vLocal) { Step(vLocal, vStep, vHomes[vIndex], null) }
			}
			exits++
			saved += vReleased.size - vKept
			return Exit(vFrom, vReleased.take(vKept).map { KRelease(KRead(it)) }, vStep)
		}

		/** Gives every step of the tree under [inStep] a label, and counts it as a release written. */
		private fun label(inStep: Step) {
			inStep.label = "ktc2_out_${mNextLabel++}"
			if (inStep.local != null) saved--
			inStep.children.values.forEach { label(it) }
		}

		/** The steps that stand in each block, in the order they are written: a ladder read downwards, its branches before their trunk. */
		private val mOrders = HashMap<Block, List<Step>>()

		/** The steps something jumps to: the others are reached by falling from the step before them, and have no label. */
		private val mTargets = HashSet<Step>()

		private fun orders() {
			val vAll = HashMap<Block, ArrayList<Step>>()
			fun visit(inStep: Step) {
				vAll.getOrPut(inStep.home) { ArrayList() } += inStep
				inStep.children.values.forEach { visit(it) }
			}
			mRoots.values.forEach { visit(it) }
			for ((vBlock, vSteps) in vAll) {
				vSteps.reverse()
				mOrders[vBlock] = vSteps
				for ((vIndex, vStep) in vSteps.withIndex()) {
					val vParent = vStep.parent ?: continue
					if (vSteps.getOrNull(vIndex + 1) !== vParent) mTargets += vParent
				}
			}
			mExits.values.forEach { vExits -> vExits.values.forEach { mTargets += it.step } }
		}

		/** [inStatements] with its ways out made jumps, and the steps that stand in it at its end. */
		private fun rewritten(inStatements: List<KStmt>, inBlock: Block): List<KStmt> {
			val vExits = mExits[inBlock.id] ?: emptyMap()
			val vOut = ArrayList<KStmt>()
			var vIndex = 0
			while (vIndex < inStatements.size) {
				val vExit = vExits[vIndex]
				if (vExit != null) {
					vOut += vExit.inline
					vOut += KGoto(vExit.step.label)
					// Through the `return`; what follows it never runs.
					while (vIndex < inStatements.size && inStatements[vIndex] !is KReturn) vIndex++
					vIndex++
					continue
				}
				vOut += when (val vStatement = inStatements[vIndex]) {
					is KIf -> KIf(vStatement.condition, rewritten(vStatement.thenBlock, nextBlock()), rewritten(vStatement.elseBlock, nextBlock()))
					is KLoop -> KLoop(vStatement.id, rewritten(vStatement.body, nextBlock()), rewritten(vStatement.latch, nextBlock()))
					else -> vStatement
				}
				vIndex++
			}
			return vOut + steps(inBlock, vOut)
		}

		/**
		 * The steps that stand in [inBlock], each its label, its release and the jump to the next step when that is
		 * not the one written after it; jumped over by the code before them when that does not end with a jump.
		 */
		private fun steps(inBlock: Block, inBefore: List<KStmt>): List<KStmt> {
			val vOrder = mOrders[inBlock] ?: return emptyList()
			val vOut = ArrayList<KStmt>()
			val vEnds = when (inBefore.lastOrNull()) {
				is KReturn, is KGoto, is KBreak, is KContinue -> true
				else -> false
			}
			val vSkip = if (vEnds) null else "ktc2_out_${mNextLabel++}"
			vSkip?.let { vOut += KGoto(it) }
			for ((vIndex, vStep) in vOrder.withIndex()) {
				if (vStep in mTargets) vOut += KLabel(vStep.label)
				val vParent = vStep.parent
				if (vParent == null) {
					vOut += vStep.returned!!
					continue
				}
				vOut += KRelease(KRead(vStep.local!!))
				if (vOrder.getOrNull(vIndex + 1) !== vParent) vOut += KGoto(vParent.label)
			}
			vSkip?.let { vOut += KLabel(it) }
			return vOut
		}
	}
}
