package ForLoopTest

fun testRangeInclusive() {
    println("--- 0..5 ---")
    var count = 0
    for (i in 0..5) {
        println(i)
        count++
    }
    if (count != 6) fatalError("FAIL 0..5 count=$count")
}

fun testRangeUntil() {
    println("--- 0 until 5 ---")
    var count = 0
    for (i in 0 until 5) {
        println(i)
        count++
    }
    if (count != 5) fatalError("FAIL 0 until 5 count=$count")
}

fun testDownTo() {
    println("--- 5 downTo 0 ---")
    var count = 0
    for (i in 5 downTo 0) {
        println(i)
        count++
    }
    if (count != 6) fatalError("FAIL 5 downTo 0 count=$count")
}

fun testStep() {
    println("--- 0..10 step 2 ---")
    var count1 = 0
    for (i in 0..10 step 2) {
        println(i)
        count1++
    }
    if (count1 != 6) fatalError("FAIL 0..10 step 2 count=$count1")
    println("--- 10 downTo 0 step 3 ---")
    var count2 = 0
    for (i in 10 downTo 0 step 3) {
        println(i)
        count2++
    }
    if (count2 != 4) fatalError("FAIL 10 downTo 0 step 3 count=$count2")
}

fun testForInArray() {
    val arr = intArrayOf(100, 200, 300)
    var sum = 0
    for (x in arr) {
        println(x)
        sum += x
    }
    if (sum != 600) fatalError("FAIL array sum=$sum")
}

// ── Char and Long ranges ─────────────────────────────────────────

fun testRangeChar() {
    println("--- 'a'..'c' ---")
    var n = 0
    for (c in 'a'..'c') {
        println(c)
        n++
    }
    if (n != 3) fatalError("FAIL char range count=$n")
}

fun testRangeLong() {
    println("--- 0L..2L ---")
    var sum = 0L
    for (i in 0L..2L) {
        sum += i
    }
    if (sum != 3L) fatalError("FAIL long range sum=$sum")
}

fun testRangeCharDownTo() {
    println("--- 'c' downTo 'a' ---")
    var n = 0
    for (c in 'c' downTo 'a') {
        println(c)
        n++
    }
    if (n != 3) fatalError("FAIL char downTo count=$n")
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
    if (fSum != 9)  fatalError("FAIL destructuring first sum=$fSum")
    if (sSum != 12) fatalError("FAIL destructuring second sum=$sSum")
}

fun testForDestructuringWithDiscard() {
    println("--- destructuring with _ ---")
    val arr = arrayOf<IntPair>(IntPair(10, 20), IntPair(30, 40))
    var sum = 0
    for ((a, _) in arr) {
        sum += a
    }
    if (sum != 40) fatalError("FAIL destructuring discard sum=$sum")
}

fun main() {
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
