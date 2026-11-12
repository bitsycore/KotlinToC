package basic

// Loops whose index is known to be inside the array, and loops that only look like them:
// the second kind must still fail as on the JVM.

fun sum(inValues: IntArray): Int {
	var vSum = 0
	for (vI in inValues.indices) vSum += inValues[vI]
	for (vI in 0 until inValues.size) vSum += inValues[vI]
	for (vValue in inValues) vSum += vValue
	return vSum
}

fun copyShifted(inFrom: IntArray, inTo: IntArray) {
	for (vI in 0 until inFrom.size) inTo[vI] = inFrom[vI] + 1
}

fun main() {
	val vValues = IntArray(8) { it * it }
	println(sum(vValues))
	val vCount = 5
	val vSquares = IntArray(vCount)
	for (vI in 0 until vCount) vSquares[vI] = vI * vI
	println(vSquares.joinToString())
	val vNames = Array(3) { "n$it" }
	for (vI in vNames.indices) vNames[vI] = vNames[vI] + vNames[vI]
	println(vNames.joinToString())

	// The bound is the length of another, longer array.
	val vShort = IntArray(3)
	try {
		copyShifted(vValues, vShort)
	} catch (vError: IndexOutOfBoundsException) {
		println("too short: " + vShort.joinToString())
	}
	try {
		for (vI in 0 until vValues.size) vShort[vI] = vI
	} catch (vError: IndexOutOfBoundsException) {
		println("too short again")
	}

	// The index is not the counter itself.
	try {
		for (vI in vValues.indices) vValues[vI + 1] = vValues[vI]
	} catch (vError: IndexOutOfBoundsException) {
		println("one too far")
	}
	// The array changes while the bound stays.
	var vArray = IntArray(6)
	val vLength = vArray.size
	try {
		for (vI in 0 until vLength) {
			vArray[vI] = vI
			if (vI == 2) vArray = IntArray(3)
		}
	} catch (vError: IndexOutOfBoundsException) {
		println("array replaced")
	}
	// A counter the body moves.
	var vIndex = 0
	var vSeen = 0
	try {
		while (vIndex < vValues.size) {
			vSeen += vValues[vIndex]
			vIndex += 3
			vSeen += vValues[vIndex]
		}
	} catch (vError: IndexOutOfBoundsException) {
		println("stepped out at $vIndex")
	}
	// Nested loops, each over its own array.
	val vGrid = Array(3) { IntArray(4) { 1 } }
	var vTotal = 0
	for (vRow in vGrid.indices) {
		for (vColumn in vGrid[vRow].indices) vTotal += vGrid[vRow][vColumn]
	}
	for (vRow in vGrid) for (vCell in vRow) vTotal += vCell
	println(vTotal)
	// A negative start and an empty array.
	val vNone = IntArray(0)
	for (vI in vNone.indices) println(vNone[vI])
	try {
		for (vI in -1 until vValues.size) vTotal += vValues[vI]
	} catch (vError: IndexOutOfBoundsException) {
		println("negative start")
	}
	println(vTotal)
}
