// `for` over a list that is known to be an ArrayList is written as a loop over its storage.
// Whatever the loop does, it has to end, skip, fail and see changes as the iterator would.

class Point(val x: Int, val y: Int)

fun sum(inList: List<Int>): Int {
	var vSum = 0
	for (vValue in inList) vSum += vValue
	return vSum
}

fun firstNegative(inList: ArrayList<Int>): Int {
	for (vValue in inList) {
		if (vValue >= 0) continue
		return vValue
	}
	return 0
}

fun attempt(inWhat: String, inBlock: () -> Any?) {
	try {
		println("$inWhat: ${inBlock()}")
	} catch (inError: ConcurrentModificationException) {
		println("$inWhat: ConcurrentModificationException")
	}
}

fun main() {
	val vNumbers = arrayListOf(4, -2, 7, -9, 1)
	println(sum(vNumbers))
	println(sum(ArrayList()))
	println(firstNegative(vNumbers))
	println(firstNegative(arrayListOf(1, 2)))

	// `break`, `continue`, and a loop inside a loop over the same list.
	var vSeen = 0
	for (vValue in vNumbers) {
		if (vValue == 7) break
		vSeen++
	}
	var vPairs = 0
	for (vLeft in vNumbers) {
		for (vRight in vNumbers) {
			if (vLeft == vRight) continue
			if (vLeft + vRight > 0) vPairs++
		}
	}
	println("$vSeen $vPairs")

	// Objects, strings, and a pair taken apart.
	val vPoints = arrayListOf(Point(1, 2), Point(3, 4))
	var vArea = 0
	for (vPoint in vPoints) vArea += vPoint.x * vPoint.y
	val vWords = arrayListOf("a", "bb", "ccc")
	var vText = ""
	for (vWord in vWords) vText += vWord.uppercase()
	val vPairList = arrayListOf(1 to "one", 2 to "two")
	for ((vNumber, vName) in vPairList) vText += " $vNumber=$vName"
	println("$vArea $vText")

	// With the index, and over what a function returns.
	for ((vIndex, vWord) in vWords.withIndex()) if (vIndex == 1) println(vWord)
	for (vValue in vNumbers.filter { it > 0 }) vSeen += vValue
	for (vValue in listOf(10, 20)) vSeen += vValue
	println(vSeen)

	// Replacing an element is no change of the list; the loop sees the new one.
	val vDoubled = arrayListOf(1, 2, 3)
	var vRound = 0
	for (vValue in vDoubled) {
		if (vRound + 1 < vDoubled.size) vDoubled[vRound + 1] = vValue * 10
		vRound++
	}
	println(vDoubled)

	// Changes while the loop runs.
	attempt("add in the loop") {
		val vList = arrayListOf(1, 2, 3)
		for (vValue in vList) if (vValue == 2) vList.add(4)
		vList
	}
	attempt("add in the last round") {
		val vList = arrayListOf(1, 2, 3)
		for (vValue in vList) if (vValue == 3) vList.add(4)
		vList
	}
	attempt("remove the last but one") {
		val vList = arrayListOf(1, 2, 3)
		for (vValue in vList) if (vValue == 2) vList.remove(vValue)
		vList
	}
	attempt("clear in the first round") {
		val vList = arrayListOf(1, 2, 3)
		var vCount = 0
		for (vValue in vList) {
			vCount += vValue
			vList.clear()
		}
		vCount
	}
	attempt("clear a list of one") {
		val vList = arrayListOf(1)
		var vCount = 0
		for (vValue in vList) {
			vCount += vValue
			vList.clear()
		}
		vCount
	}
	attempt("grow far beyond the storage") {
		val vList = arrayListOf(1, 2)
		for (vValue in vList) for (vMore in 0 until 100) vList.add(vMore)
		vList.size
	}
	attempt("changed in a function the loop calls") {
		val vList = arrayListOf(1, 2, 3)
		fun touch(inValue: Int) {
			if (inValue == 1) vList.add(9)
		}
		for (vValue in vList) touch(vValue)
		vList
	}
	attempt("an inner loop changes the list of the outer one") {
		val vOuter = arrayListOf(1, 2)
		val vInner = arrayListOf(5, 6)
		for (vLeft in vOuter) for (vRight in vInner) if (vRight == 6) vOuter.add(vRight)
		vOuter
	}

	// The iterator asked for by name is still an iterator.
	val vIterator = vNumbers.iterator()
	var vByHand = 0
	while (vIterator.hasNext()) {
		val vValue = vIterator.next()
		if (vValue < 0) vIterator.remove() else vByHand += vValue
	}
	println("$vByHand $vNumbers")
}
