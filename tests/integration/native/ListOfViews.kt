// What `listOf` gives, as on Kotlin/Native: with several elements a read-only view of its array (an index
// outside it is ArrayIndexOutOfBoundsException without a message, and it is no MutableList), with one an
// ArrayList. The JVM differs (its view of an array is a MutableList whose set works, and its messages are
// others): ListOfViews.expected is Kotlin/Native's output.

fun show(inWhat: String, inBlock: () -> Any?) {
	try {
		println("$inWhat: ${inBlock()}")
	} catch (vError: Exception) {
		println("$inWhat: ${vError::class.simpleName}: ${vError.message}")
	}
}

fun main() {
	val vList = listOf(1, 2)
	show("get out") { vList[5] }
	show("get -1") { vList[-1] }
	show("is MutableList") { vList is MutableList<*> }
	show("cast and add") {
		try {
			(vList as MutableList<Int>).add(3)
		} catch (vError: ClassCastException) {
			"refused"
		}
	}
	show("contents") { vList }
	val vOne = listOf(1)
	show("one get out") { vOne[3] }
	show("one is MutableList") { vOne is MutableList<*> }
	show("asList get out") { arrayOf(1, 2).asList()[4] }
	show("mutableListOf get out") { mutableListOf(1, 2)[4] }
	show("emptyList get") { emptyList<Int>()[0] }
	show("subList out") { vList.subList(0, 3) }
	show("toList get out") { (1..3).toList()[7] }
	show("first of empty") { emptyList<Int>().first() }
	show("string get out") { "abc"[5] }
	show("array get out") { intArrayOf(1)[3] }
}
