// Entries of a map that are kept while the map is changed in its values only: each reads and writes the value the
// map has for its key. (After a key is added or removed an entry fails with ConcurrentModificationException on
// Kotlin/Native and in ktc2, and goes on working on the JVM: tests/stdlib/MapTest.kt has that case.)

fun show(inWhat: String, inEntries: List<MutableMap.MutableEntry<String, Int>>, inMap: Map<String, Int>) {
	println("$inWhat: ${inEntries.map { "${it.key}=${it.value}" }} ${inMap.toList().sortedBy { it.first }}")
}

fun main() {
	val vMap = hashMapOf("a" to 1, "b" to 2, "c" to 3)
	val vKept = vMap.entries.sortedBy { it.key }
	show("kept", vKept, vMap)
	vMap["a"] = 10
	show("a put again", vKept, vMap)
	println("${vKept[2].setValue(30)} ${vMap["c"]} ${vKept[1].setValue(99)} ${vMap["b"]} ${vKept[1].value}")
	show("set through the entries", vKept, vMap)
	println("${vKept[0] == vMap.entries.first { it.key == "a" }} ${vKept[0].hashCode() == vMap.entries.first { it.key == "a" }.hashCode()} ${vKept[0]}")
	for (vEntry in vMap.entries) vEntry.setValue(vEntry.value + 1)
	show("each set in a loop", vKept, vMap)
	val vIterator = vMap.entries.iterator()
	while (vIterator.hasNext()) if (vIterator.next().key == "b") vIterator.remove()
	println(vMap.toList().sortedBy { it.first })
	val vLinked = linkedMapOf(1 to "one", 2 to "two")
	val vFirst = vLinked.entries.first()
	vLinked[1] = "uno"
	println("$vFirst $vLinked ${vLinked.entries.map { it.value.length }}")
	val vObjects = HashMap<String, MutableList<Int>>()
	vObjects["x"] = mutableListOf(1)
	val vEntry = vObjects.entries.first()
	vEntry.value += 2
	println("$vEntry ${vObjects["x"]}")
}
