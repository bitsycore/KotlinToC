// Entries of a map that are kept: each keeps its key, follows the value the map has for it,
// and keeps what it had once the map no longer has it.

fun show(inWhat: String, inEntries: List<MutableMap.MutableEntry<String, Int>>, inMap: Map<String, Int>) {
	println("$inWhat: ${inEntries.map { "${it.key}=${it.value}" }} $inMap")
}

fun main() {
	val vMap = hashMapOf("a" to 1, "b" to 2, "c" to 3)
	val vKept = vMap.entries.sortedBy { it.key }
	show("kept", vKept, vMap.toList().sortedBy { it.first }.toMap())
	vMap["a"] = 10
	show("a put again", vKept, vMap.toList().sortedBy { it.first }.toMap())
	vMap.remove("b")
	show("b removed", vKept, vMap.toList().sortedBy { it.first }.toMap())
	vMap["b"] = 20
	show("b added again", vKept, vMap.toList().sortedBy { it.first }.toMap())
	println("${vKept[2].setValue(30)} ${vMap["c"]} ${vKept[1].setValue(99)} ${vMap["b"]} ${vKept[1].value}")
	for (vIndex in 0 until 100) vMap["k$vIndex"] = vIndex
	vMap["a"] = 11
	show("after it grew", vKept, vMap.filterKeys { it.length == 1 }.toList().sortedBy { it.first }.toMap())
	vKept[0].setValue(12)
	println("${vMap["a"]} ${vKept[0]} ${vKept[0] == vMap.entries.first { it.key == "a" }} ${vKept[0].hashCode() == vMap.entries.first { it.key == "a" }.hashCode()}")
	val vIterator = vMap.entries.iterator()
	val vGone = ArrayList<MutableMap.MutableEntry<String, Int>>()
	while (vIterator.hasNext()) {
		val vEntry = vIterator.next()
		if (vEntry.key.length == 1) {
			vIterator.remove()
			vGone += vEntry
		}
	}
	show("removed through the iterator", vGone.sortedBy { it.key }, vMap.filterKeys { it.length == 1 })
	println("${vMap.size} ${vGone.sortedBy { it.key }.map { it.setValue(0) }} ${vMap["a"]} ${vGone.map { it.value }}")
	vMap.clear()
	show("cleared", vGone.sortedBy { it.key }, vMap)
	println(vKept[1])
	val vLinked = linkedMapOf(1 to "one", 2 to "two")
	val vFirst = vLinked.entries.first()
	vLinked.remove(1)
	vLinked[1] = "uno"
	println("$vFirst $vLinked ${vLinked.entries.map { it.value.length }}")
	val vObjects = HashMap<String, MutableList<Int>>()
	vObjects["x"] = mutableListOf(1)
	val vEntry = vObjects.entries.first()
	vObjects.remove("x")
	vEntry.value += 2
	println("$vEntry ${vObjects.size}")
}
