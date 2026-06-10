package SizedArrayTest

fun assertEq(inA: Int, inB: Int) {
    if (inA != inB) {
        println("FAIL: expected $inB got $inA")
        fatalError("assertion failed")
    }
}

fun assertTrue(inCond: Boolean, inMsg: String) {
    if (!inCond) {
        println("FAIL: $inMsg")
        fatalError("assertion failed")
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * 1. arrayOf literal returns a sized struct directly (no memcpy)
 * ══════════════════════════════════════════════════════════════════════════ */

fun makeInts(): @Size(4) IntArray = intArrayOf(10, 20, 30, 40)

fun testDirectStructReturn() {
    println("--- testDirectStructReturn ---")
    val vA = makeInts()
    assertEq(vA[0], 10)
    assertEq(vA[1], 20)
    assertEq(vA[2], 30)
    assertEq(vA[3], 40)
    assertEq(vA.size, 4)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 2. copyOf on an unsized array produces a sized target without warning
 * ══════════════════════════════════════════════════════════════════════════ */

fun testCopyOf() {
    println("--- testCopyOf ---")
    val vDyn = IntArray(5)
    vDyn[0] = 1; vDyn[1] = 2; vDyn[2] = 3; vDyn[3] = 4; vDyn[4] = 5
    val vFixed: @Size(3) IntArray = vDyn.copyOf(3)
    assertEq(vFixed[0], 1)
    assertEq(vFixed[1], 2)
    assertEq(vFixed[2], 3)
    assertEq(vFixed.size, 3)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 3. copyOf zero-pads when newSize > source size
 * ══════════════════════════════════════════════════════════════════════════ */

fun testCopyOfExpand() {
    println("--- testCopyOfExpand ---")
    val vSrc = intArrayOf(7, 8)
    val vBig: @Size(5) IntArray = vSrc.copyOf(5)
    assertEq(vBig[0], 7)
    assertEq(vBig[1], 8)
    assertEq(vBig[2], 0)
    assertEq(vBig[3], 0)
    assertEq(vBig[4], 0)
    assertEq(vBig.size, 5)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 4. copyOf on a @Size(N) array → truncate or expand
 * ══════════════════════════════════════════════════════════════════════════ */

fun makeFive(): @Size(5) IntArray = intArrayOf(1, 2, 3, 4, 5)

fun testCopyOfSized() {
    println("--- testCopyOfSized ---")
    val vFive = makeFive()
    // Truncate to 3: copy the first 3 elements
    val vThree: @Size(3) IntArray = vFive.copyOf(3)
    assertEq(vThree[0], 1)
    assertEq(vThree[1], 2)
    assertEq(vThree[2], 3)
    assertEq(vThree.size, 3)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 5. Sized function parameter round-trip
 * ══════════════════════════════════════════════════════════════════════════ */

fun sumSized(inArr: @Size(4) IntArray): Int {
    var vTotal = 0
    for (i in 0 until inArr.size) {
        vTotal += inArr[i]
    }
    return vTotal
}

fun testSizedParam() {
    println("--- testSizedParam ---")
    val vA = intArrayOf(1, 2, 3, 4)
    val vSum = sumSized(vA)
    assertEq(vSum, 10)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 6. Exact-size literal assigned to @Size(N) — inferred match, no warning
 * ══════════════════════════════════════════════════════════════════════════ */

fun testExactSizeLiteral() {
    println("--- testExactSizeLiteral ---")
    val vA: @Size(3) IntArray = intArrayOf(10, 20, 30)
    assertEq(vA[0], 10)
    assertEq(vA[1], 20)
    assertEq(vA[2], 30)
    assertEq(vA.size, 3)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 7. [TRANSPILER ERROR] Literal with more elements than the @Size(N) target.
 *    Uncomment to verify the diagnostic:
 * ══════════════════════════════════════════════════════════════════════════ */
// fun testTooLargeError() {
//     val vSmall: @Size(2) IntArray = intArrayOf(1, 2, 3, 4, 5)
//     // Error: Cannot assign @Size(5) array to @Size(2) variable 'vSmall':
//     //        source has more elements than the target (would truncate).
//     //        Use .copyOf(2) to truncate explicitly.
// }


/* ══════════════════════════════════════════════════════════════════════════
 * 8. [TRANSPILER WARNING + IMPLICIT COPY] Dynamic-size array (runtime size)
 *    assigned directly to @Size(N). The transpiler warns and silently inserts
 *    an implicit .copyOf(N), so the result is always exactly N elements.
 *    The warning only fires when the element count is unknown at transpile
 *    time (e.g. comes from a parameter). Literal sizes like IntArray(5) are
 *    inferred, so assigning them to a smaller @Size(N) is a transpiler ERROR
 *    — use .copyOf(N) explicitly.
 *
 * [TRANSPILER ERROR] Literal-sized source larger than @Size(N) target.
 *    Uncomment to verify the diagnostic:
 *
 * fun testTooLargeInferredError() {
 *     val vSrc5 = IntArray(5)            // inferred @Size(5)
 *     val vSmall: @Size(3) IntArray = vSrc5
 *     // ERROR: Cannot assign @Size(5) array to @Size(3) variable 'vSmall'
 * }
 * ══════════════════════════════════════════════════════════════════════════ */

// inSrc is a parameter: its element count is unknown at transpile time.
fun testImplicitCopyOfWarning(inSrc: IntArray) {
    println("--- testImplicitCopyOfWarning ---")
    val vTrunc: @Size(3) IntArray = inSrc   // WARN: implicit copyOf(3) inserted
    assertEq(vTrunc[0], 10)
    assertEq(vTrunc[1], 20)
    assertEq(vTrunc[2], 30)
    assertEq(vTrunc.size, 3)
    println("OK")
}

/* ═══════════════════════════════════════════════════════════════════════════
 * main
 * ═══════════════════════════════════════════════════════════════════════════ */

fun main(args: Array<String>) {
    testDirectStructReturn()
    testCopyOf()
    testCopyOfExpand()
    testCopyOfSized()
    testSizedParam()
    testExactSizeLiteral()
    testImplicitCopyOfWarning(intArrayOf(10, 20, 30, 40, 50))
    println("All tests passed!")
}
