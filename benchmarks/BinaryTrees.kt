// Benchmark: binary trees, as the benchmarks game has them. Many trees of every depth are made, walked
// and dropped: the cost of allocating and freeing small objects, and of a long-lived tree beside them.

/** A node of a perfect binary tree: both children, or none. */
class TreeNode(val left: TreeNode?, val right: TreeNode?) {
	/** How many nodes this tree has. */
	fun check(): Int = 1 + (left?.check() ?: 0) + (right?.check() ?: 0)
}

/** A perfect binary tree of [inDepth] levels below its root. */
fun bottomUp(inDepth: Int): TreeNode =
	if (inDepth > 0) TreeNode(bottomUp(inDepth - 1), bottomUp(inDepth - 1)) else TreeNode(null, null)

fun main() {
	val vMinDepth = 4
	val vMaxDepth = 18
	val vStretch = vMaxDepth + 1
	println("stretch tree of depth $vStretch\t check: ${bottomUp(vStretch).check()}")
	val vLongLived = bottomUp(vMaxDepth)
	var vDepth = vMinDepth
	while (vDepth <= vMaxDepth) {
		val vIterations = 1 shl (vMaxDepth - vDepth + vMinDepth)
		var vCheck = 0
		for (vI in 1..vIterations) vCheck += bottomUp(vDepth).check()
		println("$vIterations\t trees of depth $vDepth\t check: $vCheck")
		vDepth += 2
	}
	println("long lived tree of depth $vMaxDepth\t check: ${vLongLived.check()}")
}
