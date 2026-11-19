package ktc2test.language.loops

import kotlin.test.Test

// ==================
// MARK: BreakContinue
// ==================

fun testBreakFor() {
    var found = -1
    for (i in 0..9) {
        if (i == 5) { found = i; break }
    }
    if (found != 5) error("FAIL breakFor: $found")
    println("breakFor ok: $found")
}

fun testContinueFor() {
    var sum = 0
    for (i in 0..9) {
        if (i % 2 == 0) continue
        sum += i  // 1+3+5+7+9 = 25
    }
    if (sum != 25) error("FAIL continueFor: $sum")
    println("continueFor ok: $sum")
}

fun testBreakWhile() {
    var i = 0
    var steps = 0
    while (i < 100) {
        if (i == 7) break
        steps++
        i++
    }
    if (steps != 7) error("FAIL breakWhile: $steps")
    println("breakWhile ok: $steps")
}

fun testContinueWhile() {
    var i = 0
    var evens = 0
    while (i < 10) {
        i++
        if (i % 2 != 0) continue
        evens++  // 2,4,6,8,10 → 5
    }
    if (evens != 5) error("FAIL continueWhile: $evens")
    println("continueWhile ok: $evens")
}

fun testNestedBreakOnly() {
    // break only exits the innermost loop
    var count = 0
    for (i in 0 until 4) {
        for (j in 0 until 10) {
            if (j == 3) break
            count++  // 3 per outer → 12
        }
    }
    if (count != 12) error("FAIL nestedBreak: $count")
    println("nestedBreak ok: $count")
}

fun testBreakDoWhile() {
    var n = 0
    var steps = 0
    do {
        if (n == 4) break
        steps++
        n++
    } while (n < 100)
    if (steps != 4) error("FAIL breakDoWhile: $steps")
    println("breakDoWhile ok: $steps")
}

fun testContinueDoWhile() {
    var i = 0
    var odds = 0
    do {
        i++
        if (i % 2 == 0) continue
        odds++
    } while (i < 10)
    if (odds != 5) error("FAIL continueDoWhile: $odds")
    println("continueDoWhile ok: $odds")
}

@Test
fun breakContinue() {
    testBreakFor()
    testContinueFor()
    testBreakWhile()
    testContinueWhile()
    testNestedBreakOnly()
    testBreakDoWhile()
    testContinueDoWhile()
    println("done")
}

// ==================
// MARK: ForLoop
// ==================

fun testRangeInclusive() {
    println("--- 0..5 ---")
    var count = 0
    for (i in 0..5) {
        println(i)
        count++
    }
    if (count != 6) error("FAIL 0..5 count=$count")
}

fun testRangeUntil() {
    println("--- 0 until 5 ---")
    var count = 0
    for (i in 0 until 5) {
        println(i)
        count++
    }
    if (count != 5) error("FAIL 0 until 5 count=$count")
}

fun testDownTo() {
    println("--- 5 downTo 0 ---")
    var count = 0
    for (i in 5 downTo 0) {
        println(i)
        count++
    }
    if (count != 6) error("FAIL 5 downTo 0 count=$count")
}

fun testStep() {
    println("--- 0..10 step 2 ---")
    var count1 = 0
    for (i in 0..10 step 2) {
        println(i)
        count1++
    }
    if (count1 != 6) error("FAIL 0..10 step 2 count=$count1")
    println("--- 10 downTo 0 step 3 ---")
    var count2 = 0
    for (i in 10 downTo 0 step 3) {
        println(i)
        count2++
    }
    if (count2 != 4) error("FAIL 10 downTo 0 step 3 count=$count2")
}

fun testForInArray() {
    val arr = intArrayOf(100, 200, 300)
    var sum = 0
    for (x in arr) {
        println(x)
        sum += x
    }
    if (sum != 600) error("FAIL array sum=$sum")
}

// ── Char and Long ranges ─────────────────────────────────────────

fun testRangeChar() {
    println("--- 'a'..'c' ---")
    var n = 0
    for (c in 'a'..'c') {
        println(c)
        n++
    }
    if (n != 3) error("FAIL char range count=$n")
}

fun testRangeLong() {
    println("--- 0L..2L ---")
    var sum = 0L
    for (i in 0L..2L) {
        sum += i
    }
    if (sum != 3L) error("FAIL long range sum=$sum")
}

fun testRangeCharDownTo() {
    println("--- 'c' downTo 'a' ---")
    var n = 0
    for (c in 'c' downTo 'a') {
        println(c)
        n++
    }
    if (n != 3) error("FAIL char downTo count=$n")
}

// ── For-loop destructuring ───────────────────────────────────────

data class IntPair(val first: Int, val second: Int)

fun testForDestructuringDataClass() {
    println("--- destructuring data class ---")
    val arr = arrayOf<IntPair>(IntPair(1, 2), IntPair(3, 4), IntPair(5, 6))
    var fSum = 0
    var sSum = 0
    for ((a, b) in arr) {
        fSum += a
        sSum += b
    }
    if (fSum != 9)  error("FAIL destructuring first sum=$fSum")
    if (sSum != 12) error("FAIL destructuring second sum=$sSum")
}

fun testForDestructuringWithDiscard() {
    println("--- destructuring with _ ---")
    val arr = arrayOf<IntPair>(IntPair(10, 20), IntPair(30, 40))
    var sum = 0
    for ((a, _) in arr) {
        sum += a
    }
    if (sum != 40) error("FAIL destructuring discard sum=$sum")
}

@Test
fun forLoop() {
    testRangeInclusive()
    testRangeUntil()
    testDownTo()
    testStep()
    testForInArray()
    testRangeChar()
    testRangeLong()
    testRangeCharDownTo()
    testForDestructuringDataClass()
    testForDestructuringWithDiscard()
    println("done")
}

// ==================
// MARK: WhileDo
// ==================

fun testWhileBasic() {
    var n = 0
    while (n < 5) { n++ }
    if (n != 5) error("FAIL whileBasic: $n")
    println("whileBasic ok: $n")
}

fun testWhileProduct() {
    var n = 1
    var product = 1
    while (n <= 5) {
        product *= n
        n++
    }
    if (product != 120) error("FAIL whileProduct: $product")
    println("whileProduct ok: $product")
}

fun testDoWhileBasic() {
    var n = 0
    do {
        n++
    } while (n < 5)
    if (n != 5) error("FAIL doWhileBasic: $n")
    println("doWhileBasic ok: $n")
}

fun testDoWhileAlwaysOnce() {
    // do-while runs the body at least once even when condition is false from the start
    var ran = 0
    do {
        ran++
    } while (false)
    if (ran != 1) error("FAIL doWhileOnce: $ran")
    println("doWhileOnce ok: $ran")
}

fun testWhileBoolFlag() {
    var running = true
    var count = 0
    while (running) {
        count++
        if (count >= 3) running = false
    }
    if (count != 3) error("FAIL whileBool: $count")
    println("whileBool ok: $count")
}

fun testNestedWhile() {
    var total = 0
    var i = 0
    while (i < 4) {
        var j = 0
        while (j < 4) {
            total++
            j++
        }
        i++
    }
    if (total != 16) error("FAIL nestedWhile: $total")
    println("nestedWhile ok: $total")
}

fun testDoWhileAccumulate() {
    var sum = 0
    var x = 1
    do {
        sum += x
        x++
    } while (x <= 10)
    if (sum != 55) error("FAIL doWhileAccumulate: $sum")
    println("doWhileAccumulate ok: $sum")
}

@Test
fun whileDo() {
    testWhileBasic()
    testWhileProduct()
    testDoWhileBasic()
    testDoWhileAlwaysOnce()
    testWhileBoolFlag()
    testNestedWhile()
    testDoWhileAccumulate()
    println("done")
}

// ==================
// MARK: LoopBoundOnce
// ==================

// Proves range endpoints / steps are evaluated ONCE (Kotlin builds the progression once),
// not re-read every iteration. Pre-fix, the loop condition re-read the bound var each time, so
// mutating it in the body changed the iteration count. main returns non-zero on any mismatch.

fun mainImpl(): Int {
	// `until` upper bound: mutate it in the body - must still run the original 3 times.
	var n = 3
	var c1 = 0
	for (i in 0 until n) {
		c1 = c1 + 1
		n = 0
	}
	if (c1 != 3) {
		println("until-bound FAIL: ran $c1, expected 3")
		return 1
	}

	// `..` inclusive bound: same check (0..2 → 3 iterations).
	var m = 2
	var c2 = 0
	for (i in 0..m) {
		c2 = c2 + 1
		m = 0
	}
	if (c2 != 3) {
		println(".. bound FAIL: ran $c2, expected 3")
		return 1
	}

	// `downTo`: mutate the lower bound in the body - 5 downTo 1 → 5 iterations.
	var lo = 1
	var c3 = 0
	for (i in 5 downTo lo) {
		c3 = c3 + 1
		lo = 5
	}
	if (c3 != 5) {
		println("downTo-bound FAIL: ran $c3, expected 5")
		return 1
	}

	println("LoopBoundOnceTest OK")
	return 0
}

@Test
fun loopBoundOnce() {
	mainImpl()
}

// ==================
// MARK: RangeLoops
// ==================

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

@Test
fun rangeLoops() {
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
