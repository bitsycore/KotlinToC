// What `ArrayList` says when an index is outside it: Kotlin/Native's words (`.expected` is what
// Kotlin/Native prints; the JVM has its own).

fun main() {
	val vList = ArrayList<Int>()
	vList.add(1)
	for (vAction in 0 until 5) {
		try {
			when (vAction) {
				0 -> vList[3]
				1 -> vList[-1] = 2
				2 -> vList.removeAt(7)
				3 -> vList.add(5, 9)
				else -> vList.listIterator(4)
			}
		} catch (vError: IndexOutOfBoundsException) {
			println(vError.message)
		}
	}
}
