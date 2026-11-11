package BreakContinueTest

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

fun main() {
    testBreakFor()
    testContinueFor()
    testBreakWhile()
    testContinueWhile()
    testNestedBreakOnly()
    testBreakDoWhile()
    testContinueDoWhile()
    println("done")
}
