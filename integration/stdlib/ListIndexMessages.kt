// What `ArrayList` says when an index is outside it: the same words as the JVM.

fun main() {
	val vList = ArrayList<Int>()
	vList.add(1)
	for (vAction in 0 until 6) {
		try {
			when (vAction) {
				0 -> vList[3]
				1 -> vList[-1] = 2
				2 -> vList.removeAt(7)
				3 -> vList.add(5, 9)
				4 -> vList.listIterator(4)
				else -> listOf(1, 2, 3)[5]
			}
		} catch (vError: IndexOutOfBoundsException) {
			println(vError.message)
		}
	}
}
