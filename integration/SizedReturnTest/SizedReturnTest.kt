package SizedReturnTest

/* ── helpers ──────────────────────────────────────────────────────────────── */

fun assertEq(a: Int, b: Int) {
    if (a != b) {
        println("FAIL: expected $b got $a")
        error("assertion failed")
    }
}

fun assertTrue(cond: Boolean, msg: String) {
    if (!cond) {
        println("FAIL: $msg")
        error("assertion failed")
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * 1. Top-level function returning @Size(N) IntArray
 * ══════════════════════════════════════════════════════════════════════════ */

fun makeInts(): @Size(4) IntArray {
    val a = intArrayOf(10, 20, 30, 40)
    return a
}

fun testTopLevelIntArrayReturn() {
    println("--- testTopLevelIntArrayReturn ---")
    val a = makeInts()
    assertEq(a[0], 10)
    assertEq(a[1], 20)
    assertEq(a[2], 30)
    assertEq(a[3], 40)
    assertEq(a.size, 4)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 2. Top-level function returning @Size(N) ByteArray
 * ══════════════════════════════════════════════════════════════════════════ */

fun makeBytes(): @Size(3) ByteArray {
    val a = byteArrayOf(1, 2, 3)
    return a
}

fun testTopLevelByteArrayReturn() {
    println("--- testTopLevelByteArrayReturn ---")
    val a = makeBytes()
    assertEq(a[0].toInt(), 1)
    assertEq(a[1].toInt(), 2)
    assertEq(a[2].toInt(), 3)
    assertEq(a.size, 3)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 3. Function accepting and returning @Size(N) array (pass-through)
 * ══════════════════════════════════════════════════════════════════════════ */

fun identity4(arr: @Size(4) IntArray): @Size(4) IntArray {
    return arr
}

fun testPassThrough() {
    println("--- testPassThrough ---")
    val src = intArrayOf(7, 8, 9, 0)
    val dst = identity4(src)
    assertEq(dst[0], 7)
    assertEq(dst[1], 8)
    assertEq(dst[2], 9)
    assertEq(dst[3], 0)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 4. Class method returning @Size(N) array
 * ══════════════════════════════════════════════════════════════════════════ */

class Counter(val step: Int) {
    fun counts(): @Size(5) IntArray {
        val a = intArrayOf(step, step * 2, step * 3, step * 4, step * 5)
        return a
    }
}

fun testClassMethodReturn() {
    println("--- testClassMethodReturn ---")
    val c = Counter(3)
    val a = c.counts()
    assertEq(a[0], 3)
    assertEq(a[1], 6)
    assertEq(a[2], 9)
    assertEq(a[3], 12)
    assertEq(a[4], 15)
    assertEq(a.size, 5)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 5. Object method returning @Size(N) array
 * ══════════════════════════════════════════════════════════════════════════ */

object Lookup {
    val table: @Size(6) IntArray = intArrayOf(100, 200, 300, 400, 500, 600)

    fun getTable(): @Size(6) IntArray {
        return table
    }
}

fun testObjectMethodReturn() {
    println("--- testObjectMethodReturn ---")
    val t = Lookup.getTable()
    assertEq(t[0], 100)
    assertEq(t[2], 300)
    assertEq(t[5], 600)
    assertEq(t.size, 6)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 6. Chained calls: result of one sized-return call passed to another
 * ══════════════════════════════════════════════════════════════════════════ */

fun double4(arr: @Size(4) IntArray): @Size(4) IntArray {
    val r = intArrayOf(arr[0] * 2, arr[1] * 2, arr[2] * 2, arr[3] * 2)
    return r
}

fun testChainedCalls() {
    println("--- testChainedCalls ---")
    val result = double4(makeInts())
    assertEq(result[0], 20)
    assertEq(result[1], 40)
    assertEq(result[2], 60)
    assertEq(result[3], 80)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 7. Top-level function returning @Size(N) String
 * ══════════════════════════════════════════════════════════════════════════ */

fun greet(name: String): @Size(32) String {
    return "Hello, $name!"
}

fun testStringReturn() {
    println("--- testStringReturn ---")
    val s = greet("World")
    assertTrue(s.startsWith("Hello"), "should start with Hello")
    assertTrue(s.contains("World"), "should contain World")
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 8. Class method returning @Size(N) String
 * ══════════════════════════════════════════════════════════════════════════ */

class Greeter(val prefix: String) {
    fun greet(name: String): @Size(64) String {
        return "$prefix $name"
    }
}

fun testClassMethodStringReturn() {
    println("--- testClassMethodStringReturn ---")
    val g = Greeter("Hi")
    val s = g.greet("Alice")
    assertTrue(s.startsWith("Hi"), "should start with Hi")
    assertTrue(s.contains("Alice"), "should contain Alice")
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 9. @Size(N) String as function parameter
 * ══════════════════════════════════════════════════════════════════════════ */

fun strLen(s: @Size(32) String): Int {
    return s.length
}

fun testSizedStringParam() {
    println("--- testSizedStringParam ---")
    val s = greet("Bob")
    val n = strLen(s)
    assertTrue(n > 0, "length should be positive")
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 10. Multiple sized returns in same scope (different types and sizes)
 * ══════════════════════════════════════════════════════════════════════════ */

fun makeSmall(): @Size(2) IntArray  { return intArrayOf(1, 2)           }
fun makeLarge(): @Size(8) IntArray  { return intArrayOf(1,2,3,4,5,6,7,8) }
fun makeMedium(): @Size(4) IntArray { return intArrayOf(10, 20, 30, 40) }

fun testMultipleSizes() {
    println("--- testMultipleSizes ---")
    val small  = makeSmall()
    val large  = makeLarge()
    val medium = makeMedium()
    assertEq(small.size,  2)
    assertEq(large.size,  8)
    assertEq(medium.size, 4)
    assertEq(small[0],  1)
    assertEq(large[7],  8)
    assertEq(medium[2], 30)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 11. Overloaded functions with different @Size returns
 * ══════════════════════════════════════════════════════════════════════════ */

fun fill(n: Int): @Size(3) IntArray { return intArrayOf(n, n, n)        }
fun fill(a: Int, b: Int): @Size(4) IntArray { return intArrayOf(a, b, a, b) }

fun testOverloadedSizedReturn() {
    println("--- testOverloadedSizedReturn ---")
    val a = fill(5)
    val b = fill(1, 2)
    assertEq(a[0], 5)
    assertEq(a[2], 5)
    assertEq(b[0], 1)
    assertEq(b[1], 2)
    assertEq(b[3], 2)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 12. Using .size on a @Size(N) array param
 * ══════════════════════════════════════════════════════════════════════════ */

fun sumSized(arr: @Size(5) IntArray): Int {
    var total = 0
    for (i in 0 until arr.size) {
        total += arr[i]
    }
    return total
}

fun testSizedParamSize() {
    println("--- testSizedParamSize ---")
    val a = intArrayOf(1, 2, 3, 4, 5)
    val s = sumSized(a)
    assertEq(s, 15)
    println("OK")
}

/* ═══════════════════════════════════════════════════════════════════════════
 * main
 * ═══════════════════════════════════════════════════════════════════════════ */

fun main(args: Array<String>) {
    testTopLevelIntArrayReturn()
    testTopLevelByteArrayReturn()
    testPassThrough()
    testClassMethodReturn()
    testObjectMethodReturn()
    testChainedCalls()
    testStringReturn()
    testClassMethodStringReturn()
    testSizedStringParam()
    testMultipleSizes()
    testOverloadedSizedReturn()
    testSizedParamSize()
    println("All tests passed!")
}
