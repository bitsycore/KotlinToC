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
	moreShapes()
}

/** What happens, as text: a result or the kind of failure. */
fun outcome(inBlock: () -> Int): String = try {
	inBlock().toString()
} catch (inError: IndexOutOfBoundsException) {
	"out of bounds"
}

/** `while` loops, loops that go down, and an index next to the counter: in range or not, the answer is the JVM's. */
fun moreShapes() {
	val vValues = intArrayOf(3, 1, 4, 1, 5, 9)
	val vEmpty = IntArray(0)
	// A `while` whose step is in the body.
	println(outcome {
		var vSum = 0
		var vIndex = 0
		while (vIndex < vValues.size) {
			vSum += vValues[vIndex]
			vIndex++
		}
		vSum
	})
	// The element is read after the step: the last round is one too far.
	println(outcome {
		var vSum = 0
		var vIndex = 0
		while (vIndex < vValues.size) {
			vIndex++
			vSum += vValues[vIndex]
		}
		vSum
	})
	// A copy made before the step still names the element of this round.
	println(outcome {
		var vSum = 0
		var vIndex = 0
		while (vIndex < vValues.size) {
			val vHere = vIndex
			vIndex++
			vSum += vValues[vHere]
		}
		vSum
	})
	// A step of two under a test for one.
	println(outcome {
		var vSum = 0
		var vIndex = 0
		while (vIndex < vValues.size) {
			vSum += vValues[vIndex] + vValues[vIndex + 1]
			vIndex += 2
		}
		vSum
	})
	// Down to zero.
	println(outcome {
		var vSum = 0
		for (vIndex in vValues.size - 1 downTo 0) vSum = vSum * 2 + vValues[vIndex]
		vSum
	})
	println(outcome {
		var vSum = 0
		for (vIndex in vEmpty.size - 1 downTo 0) vSum += vEmpty[vIndex]
		vSum
	})
	println(outcome {
		var vSum = 0
		for (vIndex in vValues.size - 2 downTo 0) vSum += vValues[vIndex] - vValues[vIndex + 1]
		vSum
	})
	// One above the counter, from the last element down: out at once.
	println(outcome {
		var vSum = 0
		for (vIndex in vValues.size - 1 downTo 0) vSum += vValues[vIndex + 1]
		vSum
	})
	// One below the counter, down to zero: out at the end.
	println(outcome {
		var vSum = 0
		for (vIndex in vValues.size - 1 downTo 0) vSum += vValues[vIndex - 1]
		vSum
	})
	println(outcome {
		var vSum = 0
		for (vIndex in vValues.size downTo 0) vSum += vValues[vIndex]
		vSum
	})
	// Neighbours of the counter.
	println(outcome {
		var vSum = 0
		for (vIndex in 0 until vValues.size - 1) vSum += vValues[vIndex] * vValues[vIndex + 1]
		vSum
	})
	println(outcome {
		var vSum = 0
		for (vIndex in 0 until vValues.size - 1) vSum += vValues[vIndex + 2]
		vSum
	})
	println(outcome {
		var vSum = 0
		for (vIndex in 1 until vValues.size) vSum += vValues[vIndex] - vValues[vIndex - 1]
		vSum
	})
	println(outcome {
		var vSum = 0
		for (vIndex in 1 until vValues.size) vSum += vValues[vIndex - 2]
		vSum
	})
	println(outcome {
		var vSum = 0
		for (vIndex in 0 until vEmpty.size - 1) vSum += vEmpty[vIndex + 1]
		vSum
	})
	// Another array than the one the bound was read from.
	val vShort = intArrayOf(7, 8)
	println(outcome {
		var vSum = 0
		for (vIndex in vValues.size - 1 downTo 0) vSum += vShort[vIndex]
		vSum
	})
	println(outcome {
		var vSum = 0
		var vIndex = 0
		while (vIndex < vValues.size) {
			vSum += vShort[vIndex]
			vIndex++
		}
		vSum
	})
	// Writing, in the three shapes.
	val vTarget = IntArray(6)
	var vAt = 0
	while (vAt < vTarget.size) {
		vTarget[vAt] = vAt * vAt
		vAt++
	}
	for (vIndex in vTarget.size - 1 downTo 0) vTarget[vIndex] += 1
	for (vIndex in 0 until vTarget.size - 1) vTarget[vIndex + 1] += vTarget[vIndex]
	println(vTarget.joinToString())
}
