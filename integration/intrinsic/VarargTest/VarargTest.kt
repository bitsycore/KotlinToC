package VarargTest

fun sum(vararg nums: Int): Int {
    var total = 0
    for (n in nums) { total += n }
    return total
}

fun product(vararg nums: Int): Int {
    var result = 1
    for (n in nums) { result *= n }
    return result
}

fun count(vararg nums: Int): Int = nums.size

fun maxVal(first: Int, vararg rest: Int): Int {
    var m = first
    for (n in rest) { if (n > m) m = n }
    return m
}

// Counts the bytes a join would produce. Used to size the destination buffer
// from main before doing the actual concatenation locally - avoids returning a
// String that would alias the callee's stack frame.
fun joinedLen(sep: String, vararg parts: String): Int {
    var total = 0
    var i = 0
    while (i < parts.size) {
        if (i > 0) total += sep.length
        total += parts[i].length
        i++
    }
    return total
}

fun main() {
    // Empty vararg
    val s0 = sum()
    if (s0 != 0) fatalError("FAIL sum(): $s0")
    println("sum() = $s0")

    // Single arg
    val s1 = sum(42)
    if (s1 != 42) fatalError("FAIL sum(42): $s1")
    println("sum(42) = $s1")

    // Multiple args
    val s5 = sum(1, 2, 3, 4, 5)
    if (s5 != 15) fatalError("FAIL sum(1..5): $s5")
    println("sum(1,2,3,4,5) = $s5")

    // Count
    val c3 = count(10, 20, 30)
    if (c3 != 3) fatalError("FAIL count: $c3")
    println("count(3 args) = $c3")

    // Product
    val p = product(2, 3, 4)
    if (p != 24) fatalError("FAIL product: $p")
    println("product(2,3,4) = $p")

    // Leading required param + vararg
    val m = maxVal(3, 1, 9, 2, 7)
    if (m != 9) fatalError("FAIL maxVal: $m")
    println("maxVal = $m")

    // String vararg via length-sum helper (build the join locally to keep the
    // resulting buffer in main's frame, not a callee's).
    val joinLen = joinedLen("-", "a", "b", "c")
    if (joinLen != 5) fatalError("FAIL joinedLen: $joinLen")
    println("joinedLen = $joinLen")

    // Spread from array
    val arr = intArrayOf(10, 20, 30)
    val s = sum(*arr)
    if (s != 60) fatalError("FAIL spread: $s")
    println("spread sum = $s")

    println("done")
}
