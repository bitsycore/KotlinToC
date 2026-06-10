package ArenaTest

data class Vec2(val x: Float, val y: Float)

fun assertEq(inA: Int, inB: Int) {
    if (inA != inB) {
        println("FAIL: expected $inB got $inA")
        fatalError("assertion failed")
    }
}

fun assertEqF(inA: Float, inB: Float) {
    if (inA != inB) {
        println("FAIL: expected $inB got $inA")
        fatalError("assertion failed")
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * 1. Stack-backed arena — bump allocation and reset
 * ══════════════════════════════════════════════════════════════════════════ */

fun testStackArena() {
    println("--- testStackArena ---")
    val vBuf = ByteArray(512)
    val vArena = Arena(vBuf.asRef(), vBuf.size)
    assertEq(vArena.used(), 0)
    assertEq(vArena.remaining(), 512)

    val pVec: Ref<Vec2> = Vec2(3.0f, 4.0f).allocWith(vArena)
    assertEqF(pVec.x, 3.0f)
    assertEqF(pVec.y, 4.0f)

    val vUsed = vArena.used()
    if (vUsed <= 0) fatalError("used should be > 0 after allocWith")
    if (vArena.remaining() != 512 - vUsed) fatalError("remaining mismatch")

    vArena.reset()
    assertEq(vArena.used(), 0)
    assertEq(vArena.remaining(), 512)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 2. Heap-backed arena — caller owns the backing buffer
 * ══════════════════════════════════════════════════════════════════════════ */

fun testHeapArena() {
    println("--- testHeapArena ---")
    val vBuf: Ref<RawArray<Byte>> = RawArray<Byte>(256).allocWith(Heap)!!
    defer Heap.freeMem(vBuf)
    val vArena = Arena(vBuf, 256)

    val pVec: Ref<Vec2> = Vec2(7.0f, 8.0f).allocWith(vArena)
    assertEqF(pVec.x, 7.0f)
    assertEqF(pVec.y, 8.0f)

    if (vArena.used() <= 0) fatalError("used should be > 0")
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 3. Interface dispatch — Arena passed as Allocator
 * ══════════════════════════════════════════════════════════════════════════ */

fun allocVec(inAlloc: Allocator, inX: Float, inY: Float): Ref<Vec2> {
    return Vec2(inX, inY).allocWith(inAlloc)
}

fun testInterfaceDispatch() {
    println("--- testInterfaceDispatch ---")
    val vBuf = ByteArray(256)
    val vArena = Arena(vBuf.asRef(), vBuf.size)

    val pVec: Ref<Vec2> = allocVec(vArena, 1.5f, 2.5f)
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
    val vArena = Arena(vBuf.asRef(), vBuf.size)

    val vSb = vArena.stringBuffer(128)
    if (vArena.used() <= 0) fatalError("stringBuffer should consume arena space")

    // Use the arena-backed StringBuffer for a data class toString
    val vVec = Vec2(1.5f, 2.5f)
    val vStr = vVec.toString(vSb)
    if (vStr != "Vec2(x=1.5, y=2.5)") {
        println("FAIL: got '$vStr'")
        fatalError("string content wrong")
    }

    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 5. Multiple allocations — verify bump pointer advances correctly
 * ══════════════════════════════════════════════════════════════════════════ */

fun testMultipleAllocs() {
    println("--- testMultipleAllocs ---")
    val vBuf = ByteArray(1024)
    val vArena = Arena(vBuf.asRef(), vBuf.size)

    val pA: Ref<Vec2> = Vec2(1.0f, 2.0f).allocWith(vArena)
    val pB: Ref<Vec2> = Vec2(3.0f, 4.0f).allocWith(vArena)
    val pC: Ref<Vec2> = Vec2(5.0f, 6.0f).allocWith(vArena)

    assertEqF(pA.x, 1.0f); assertEqF(pA.y, 2.0f)
    assertEqF(pB.x, 3.0f); assertEqF(pB.y, 4.0f)
    assertEqF(pC.x, 5.0f); assertEqF(pC.y, 6.0f)

    // Reset and reuse — previous pointers become invalid but arena is clean
    vArena.reset()
    assertEq(vArena.used(), 0)

    val pD: Ref<Vec2> = Vec2(9.0f, 10.0f).allocWith(vArena)
    assertEqF(pD.x, 9.0f); assertEqF(pD.y, 10.0f)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 6. used() / remaining() invariants across allocations
 * ══════════════════════════════════════════════════════════════════════════ */

fun testCapacityInvariants() {
    println("--- testCapacityInvariants ---")
    val vBuf = ByteArray(256)
    val vArena = Arena(vBuf.asRef(), vBuf.size)

    // After every alloc: used + remaining must equal the original capacity.
    val total = 256
    var prevUsed = 0
    for (i in 0 until 4) {
        Vec2(i.toFloat(), (i * 2).toFloat()).allocWith(vArena)
        val u = vArena.used()
        val r = vArena.remaining()
        if (u + r != total) fatalError("invariant broken at $i: used=$u remaining=$r")
        if (u <= prevUsed) fatalError("used didn't grow at $i: prev=$prevUsed now=$u")
        prevUsed = u
    }

    // After reset(): used == 0, remaining == cap.
    vArena.reset()
    if (vArena.used() != 0) fatalError("after reset used=${vArena.used()}")
    if (vArena.remaining() != total) fatalError("after reset remaining=${vArena.remaining()}")
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
    testCapacityInvariants()
    println("All tests passed!")
}
