// Benchmark: a program that starts threads, so that every count on a shared object is atomic.

import ktc2.concurrent.Thread
import ktc2.concurrent.synchronized
import ktc2.concurrent.thread

/** A value several threads add to under a lock. */
class Shared {
	var total = 0L
}

/** A node of a tree built and walked by one thread only. */
class Tree(val left: Tree?, val right: Tree?, val value: Int)

fun build(inDepth: Int): Tree = if (inDepth == 0) Tree(null, null, 1) else Tree(build(inDepth - 1), build(inDepth - 1), inDepth)

fun weigh(inTree: Tree?): Int = if (inTree == null) 0 else inTree.value + weigh(inTree.left) + weigh(inTree.right)

fun main() {
	val vShared = Shared()
	val vThreads = ArrayList<Thread>()
	for (vT in 0 until 4) {
		vThreads.add(thread {
			var vLocal = 0L
			for (vRound in 0 until 40) vLocal += weigh(build(14))
			for (vI in 0 until 20000) {
				synchronized(vShared) { vShared.total += 1 }
			}
			synchronized(vShared) { vShared.total += vLocal }
		})
	}
	for (vThread in vThreads) vThread.join()
	println(vShared.total)
	val vList = ArrayList<Tree>()
	for (vI in 0 until 300000) vList.add(Tree(null, null, vI))
	var vSum = 0L
	for (vRound in 0 until 200) {
		for (vTree in vList) vSum += vTree.value
	}
	println(vSum)
}
