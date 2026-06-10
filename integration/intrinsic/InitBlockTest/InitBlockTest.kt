package InitBlockTest

/* ══════════════════════════════════════════════════════════════════════════
 * 1. Single init block initializing a body prop from a ctor param
 * ══════════════════════════════════════════════════════════════════════════ */

class Player(val name: String) {
    var health: Int = 0
    var ready: Boolean = false
    init {
        health = 100
        ready  = true
    }
}

fun testSingleInit() {
    println("--- testSingleInit ---")
    val p = Player("Alice")
    if (p.name != "Alice") fatalError("name=${p.name}")
    if (p.health != 100)   fatalError("health=${p.health}")
    if (!p.ready)          fatalError("ready false")
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 2. Multiple init blocks run in declaration order
 * ══════════════════════════════════════════════════════════════════════════ */

class Counters(val start: Int) {
    var a: Int = 0
    var b: Int = 0
    init { a = start }
    init { b = start + 1 }
}

fun testMultipleInits() {
    println("--- testMultipleInits ---")
    val c = Counters(10)
    if (c.a != 10) fatalError("a=${c.a}")
    if (c.b != 11) fatalError("b=${c.b}")
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 3. Init block populating an allocated buffer from a ctor param
 *    (mirrors how HashMap.occ is zeroed; the regression this guards against)
 * ══════════════════════════════════════════════════════════════════════════ */

class FilledBuf(val capacity: Int) {
    var buf: Ref<RawArray<Int>> = RawArray<Int>(capacity).allocWith(Heap)!!
    init {
        buf.fill(capacity, 42)
    }
}

fun testInitFillsAllocatedBuffer() {
    println("--- testInitFillsAllocatedBuffer ---")
    val f = FilledBuf(8)
    for (i in 0 until 8) {
        if (f.buf[i] != 42) fatalError("buf[$i] = ${f.buf[i]}, expected 42")
    }
    Heap.freeMem(f.buf)
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 4. Init block can call methods on $self
 * ══════════════════════════════════════════════════════════════════════════ */

class Accum(val seed: Int) {
    var sum: Int = 0
    fun add(x: Int) { sum = sum + x }
    init {
        add(seed)
        add(seed * 2)
        add(seed * 3)
    }
}

fun testInitCallsMethod() {
    println("--- testInitCallsMethod ---")
    val a = Accum(5)
    // 5 + 10 + 15 = 30
    if (a.sum != 30) fatalError("sum=${a.sum}")
    println("OK")
}

/* ══════════════════════════════════════════════════════════════════════════
 * 5. Init block with control flow
 * ══════════════════════════════════════════════════════════════════════════ */

class Clamped(val value: Int) {
    var clipped: Int = 0
    init {
        if (value < 0) {
            clipped = 0
        } else if (value > 100) {
            clipped = 100
        } else {
            clipped = value
        }
    }
}

fun testInitWithControlFlow() {
    println("--- testInitWithControlFlow ---")
    val low  = Clamped(-5)
    val mid  = Clamped(42)
    val high = Clamped(200)
    if (low.clipped  != 0)   fatalError("low=${low.clipped}")
    if (mid.clipped  != 42)  fatalError("mid=${mid.clipped}")
    if (high.clipped != 100) fatalError("high=${high.clipped}")
    println("OK")
}

fun main(args: Array<String>) {
    testSingleInit()
    testMultipleInits()
    testInitFillsAllocatedBuffer()
    testInitCallsMethod()
    testInitWithControlFlow()
    println("All init-block tests passed!")
}
