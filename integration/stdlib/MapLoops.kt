// `for` over a map, its keys or its values is written as a loop over the map's storage. It
// has to see what the iterator sees: every live entry once, in order, the holes left by
// removals skipped, and a change of the map as ConcurrentModificationException.

fun attempt(inWhat: String, inBlock: () -> Any?) {
	try {
		println("$inWhat: ${inBlock()}")
	} catch (inError: ConcurrentModificationException) {
		println("$inWhat: ConcurrentModificationException")
	}
}

fun weigh(inMap: Map<String, Int>): Int {
	var vWeight = 0
	for ((vKey, vValue) in inMap) vWeight += vKey.length * vValue
	return vWeight
}

fun main() {
	val vMap = linkedMapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4)
	var vText = ""
	for ((vKey, vValue) in vMap) vText += "$vKey=$vValue "
	for (vKey in vMap.keys) vText += vKey.first()
	for (vValue in vMap.values) vText += vValue
	println(vText)
	println(weigh(vMap))
	println(weigh(LinkedHashMap()))

	// Holes: removed keys are skipped, also the first and the last.
	vMap.remove("one")
	vMap.remove("four")
	vMap["five"] = 5
	vText = ""
	for ((vKey, vValue) in vMap) vText += "$vKey=$vValue "
	for (vEntry in vMap) vText += vEntry.key.length + vEntry.value
	println(vText)

	// A hash map: the order is its own, the content is not.
	val vHash = HashMap<Int, String>()
	for (vIndex in 0 until 50) vHash[vIndex * 37] = "v$vIndex"
	for (vIndex in 0 until 50 step 3) vHash.remove(vIndex * 37)
	var vKeys = 0
	var vLength = 0
	var vCount = 0
	for ((vKey, vValue) in vHash) {
		vKeys += vKey
		vLength += vValue.length
		vCount++
	}
	for (vKey in vHash.keys) vKeys -= vKey
	for (vValue in vHash.values) vLength -= vValue.length
	println("$vCount $vKeys $vLength ${vHash.size}")

	// A set is a map of its elements.
	val vSet = linkedSetOf("a", "b", "c")
	vSet.remove("b")
	vText = ""
	for (vElement in vSet) vText += vElement
	val vNumbers = hashSetOf(3, 5, 8)
	var vSum = 0
	for (vNumber in vNumbers) vSum += vNumber
	println("$vText $vSum")

	// `break`, `continue`, a loop in a loop, and a value replaced on the way (no change of the map).
	var vSeen = 0
	for ((vKey, vValue) in vMap) {
		if (vValue == 2) continue
		if (vKey == "five") break
		vSeen += vValue
	}
	var vPairs = 0
	for (vLeft in vMap.keys) for (vRight in vMap.values) if (vLeft.length == vRight) vPairs++
	for ((vKey, vValue) in vMap) vMap[vKey] = vValue * 10
	println("$vSeen $vPairs $vMap")

	// The entry itself is wanted: it is an object as before.
	val vEntries = ArrayList<Map.Entry<String, Int>>()
	for (vEntry in vMap) vEntries.add(vEntry)
	for (vEntry in vMap.entries) if (vEntry.key == "two") vEntry.setValue(22)
	println("${vEntries.size} ${vEntries[0].key} ${vEntries[0]} ${vMap["two"]}")

	// Changes while the loop runs.
	attempt("put a new key") {
		val vOther = linkedMapOf(1 to "a", 2 to "b", 3 to "c")
		for ((vKey, _) in vOther) if (vKey == 1) vOther[9] = "z"
		vOther
	}
	attempt("put a new key at the last entry") {
		val vOther = linkedMapOf(1 to "a", 2 to "b")
		for ((vKey, _) in vOther) if (vKey == 2) vOther[9] = "z"
		vOther
	}
	attempt("remove a key going over the keys") {
		val vOther = linkedMapOf(1 to "a", 2 to "b", 3 to "c")
		for (vKey in vOther.keys) if (vKey == 1) vOther.remove(3)
		vOther
	}
	attempt("clear going over the values") {
		val vOther = linkedMapOf(1 to "a", 2 to "b", 3 to "c")
		var vJoined = ""
		for (vValue in vOther.values) {
			vJoined += vValue
			vOther.clear()
		}
		vJoined
	}
	attempt("grow far beyond the storage") {
		val vOther = linkedMapOf(1 to 1, 2 to 2)
		for ((vKey, _) in vOther) for (vMore in 10 until 200) vOther[vMore + vKey] = vMore
		vOther.size
	}
	attempt("a set changed on the way") {
		val vOther = linkedSetOf(1, 2, 3)
		for (vElement in vOther) if (vElement == 2) vOther.add(4)
		vOther
	}
	attempt("changed in a function the loop calls") {
		val vOther = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
		fun touch(inKey: String) {
			if (inKey == "a") vOther.remove("b")
		}
		for ((vKey, _) in vOther) touch(vKey)
		vOther
	}
}
