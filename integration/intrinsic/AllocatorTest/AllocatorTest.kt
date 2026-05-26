package AllocatorTest

data class Vec2(val x: Float, val y: Float)

fun testAlloc(alloc: Allocator, size: Int): Ref<Byte> {
    return alloc.allocMem(size)
}

fun main() {
    println("=== alloc / free === ${Macro.FILE}")
    val p1: Ref<Byte> = Heap.allocMem(64)
    println("Heap.allocMem(64): ok")
    Heap.freeMem(p1)
    println("Heap.freeMem: ok")

    println()
    println("=== realloc ===")
    val p2: Ref<Byte> = Heap.allocMem(16)
    val p3: Ref<Byte> = Heap.reallocMem(p2, 32)
    println("realloc 16->32: ok")
    Heap.freeMem(p3)

    println()
    println("=== multiple allocs ===")
    val pA = Heap.allocMem(128)
    val pB = Heap.allocMem(256)
    val pC = Heap.allocMem(512)
    Heap.freeMem(pA)
    Heap.freeMem(pB)
    Heap.freeMem(pC)
    println("3 allocs + frees: ok")

    println()
    println("=== interface dispatch ===")
    val p4: Ref<Byte> = testAlloc(Heap, 256)
    println("interface alloc(256): ok")
    Heap.freeMem(p4)

    println()
    println("=== allocWith constructor ===")
    val pv: Ref<Vec2> = Vec2(10.0f, 20.0f).allocWith(Heap)
    defer Heap.freeMem(pv)
    println("allocWith Vec2 x=" + pv.x.toString() + " y=" + pv.y.toString())
    if (pv.x != 10.0f || pv.y != 20.0f) error("FAIL allocWith values")
    println("allocWith Vec2: ok")

    println()
    println("=== defer ===")
    val p5: Ref<Byte> = Heap.allocMem(32)
    defer Heap.freeMem(p5)
    println("defer alloc+free: ok")

    println()
    println("ALL OK")
}
