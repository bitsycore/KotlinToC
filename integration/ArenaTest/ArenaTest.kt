package ArenaTest

data class Vec2(val x: Float, val y: Float)

fun assertEq(inA: Int, inB: Int) {
    if (inA != inB) {
        println("FAIL: expected $inB got $inA")
        error("assertion failed")
    }
}

fun assertEqF(inA: Float, inB: Float) {
    if (inA != inB) {
        println("FAIL: expected $inB got $inA")
        error("assertion failed")
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * 1. Stack-backed arena — bump allocation and reset
 * ══════════════════════════════════════════════════════════════════════════ */

fun testStackArena() {
    println("--- testStackArena ---")
    val vBuf = ByteArray(512)
    val vArena = Arena(vBuf.ptr(), vBuf.size)
    assertEq(vArena.used(), 0)
    assertEq(vArena.remaining(), 512)

    val pVec: @Ptr Vec2 = Vec2(3.0f, 4.0f).allocWith(vArena)
    assertEqF(pVec.x, 3.0f)
    assertEqF(pVec.y, 4.0f)

    val vUsed = vArena.used()
    if (vUsed <= 0) error("used should be > 0 after allocWith")
    if (vArena.remaining() != 512 - vUsed) error("remaining mismatch")

    vArena.reset()
    assertEq(vArena.used(), 0)
    assertEq(vArena.remaining(), 512)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 2. Heap-backed arena
 * ══════════════════════════════════════════════════════════════════════════ */

fun testHeapArena() {
    println("--- testHeapArena ---")
    val vArena = Arena(256)
    defer vArena.free()

    val pVec: @Ptr Vec2 = Vec2(7.0f, 8.0f).allocWith(vArena)
    assertEqF(pVec.x, 7.0f)
    assertEqF(pVec.y, 8.0f)

    if (vArena.used() <= 0) error("used should be > 0")
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 3. Interface dispatch — Arena passed as Allocator
 * ══════════════════════════════════════════════════════════════════════════ */

fun allocVec(inAlloc: Allocator, inX: Float, inY: Float): @Ptr Vec2 {
    return Vec2(inX, inY).allocWith(inAlloc)
}

fun testInterfaceDispatch() {
    println("--- testInterfaceDispatch ---")
    val vBuf = ByteArray(256)
    val vArena = Arena(vBuf.ptr(), vBuf.size)

    val pVec: @Ptr Vec2 = allocVec(vArena, 1.5f, 2.5f)
    assertEqF(pVec.x, 1.5f)
    assertEqF(pVec.y, 2.5f)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 4. StringBuffer backed by arena
 * ══════════════════════════════════════════════════════════════════════════ */

fun testArenaStringBuffer() {
    println("--- testArenaStringBuffer ---")
    val vBuf = ByteArray(512)
    val vArena = Arena(vBuf.ptr(), vBuf.size)

    val vSb = vArena.stringBuffer(128)
    if (vArena.used() <= 0) error("stringBuffer should consume arena space")

    // Use the arena-backed StringBuffer for a data class toString
    val vVec = Vec2(1.5f, 2.5f)
    val vStr = vVec.toString(vSb)
    if (vStr != "Vec2(x=1.5, y=2.5)") {
        println("FAIL: got '$vStr'")
        error("string content wrong")
    }

    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 5. Multiple allocations — verify bump pointer advances correctly
 * ══════════════════════════════════════════════════════════════════════════ */

fun testMultipleAllocs() {
    println("--- testMultipleAllocs ---")
    val vBuf = ByteArray(1024)
    val vArena = Arena(vBuf.ptr(), vBuf.size)

    val pA: @Ptr Vec2 = Vec2(1.0f, 2.0f).allocWith(vArena)
    val pB: @Ptr Vec2 = Vec2(3.0f, 4.0f).allocWith(vArena)
    val pC: @Ptr Vec2 = Vec2(5.0f, 6.0f).allocWith(vArena)

    assertEqF(pA.x, 1.0f); assertEqF(pA.y, 2.0f)
    assertEqF(pB.x, 3.0f); assertEqF(pB.y, 4.0f)
    assertEqF(pC.x, 5.0f); assertEqF(pC.y, 6.0f)

    // Reset and reuse — previous pointers become invalid but arena is clean
    vArena.reset()
    assertEq(vArena.used(), 0)

    val pD: @Ptr Vec2 = Vec2(9.0f, 10.0f).allocWith(vArena)
    assertEqF(pD.x, 9.0f); assertEqF(pD.y, 10.0f)
    println("OK")
}

/* ═══════════════════════════════════════════════════════════════════════════
 * main
 * ═══════════════════════════════════════════════════════════════════════════ */

fun main(args: Array<String>) {
    testStackArena()
    testHeapArena()
    testInterfaceDispatch()
    testArenaStringBuffer()
    testMultipleAllocs()
    println("All tests passed!")
}
