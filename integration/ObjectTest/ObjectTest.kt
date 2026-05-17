package ObjectTest

object Config {
    private var count: Int = 0

    fun next(): Int {
        count++
        return count
    }
}

class SubConfig {

    private val buffer: @Ptr Array<Int> = Array.allocWith(Heap, 128)
    private val buffer2: @Ptr Array<Int> = Array<Int>.allocWith(Heap, 128)
    private val buffer3 = Array<Int>.allocWith(Heap, 128)

    private val rawBuffer: @Ptr RawArray<Float> = RawArray.allocWith(Heap, 128)
    private val rawBuffer2: @Ptr RawArray<Float> = RawArray<Float>.allocWith(Heap, 128)
    private val rawBuffer3 = RawArray<Float>.allocWith(Heap, 128)

    override fun dispose() {
        println("AutoFreeing SubConfig")
        Heap.freeMem(buffer)
        Heap.freeMem(buffer2)
        Heap.freeMem(buffer3)
        Heap.freeMem(rawBuffer)
        Heap.freeMem(rawBuffer2)
        Heap.freeMem(rawBuffer3)
    }

}

object Config2 {

    val sub = SubConfig()

    val buffer: @Ptr Array<Int> = Array.allocWith(Heap, 128)
    val buffer2: @Ptr Array<Int> = Array<Int>.allocWith(Heap, 128)
    val buffer3 = Array<Int>.allocWith(Heap, 128)

    val rawBuffer: @Ptr RawArray<Float> = RawArray.allocWith(Heap, 128)
    val rawBuffer2: @Ptr RawArray<Float> = RawArray<Float>.allocWith(Heap, 128)
    val rawBuffer3 = RawArray<Float>.allocWith(Heap, 128)

    override fun dispose() {
        println("AutoFreeing Config2")
        Heap.freeMem(buffer)
        Heap.freeMem(buffer2)
        Heap.freeMem(buffer3)
        Heap.freeMem(rawBuffer)
        Heap.freeMem(rawBuffer2)
        Heap.freeMem(rawBuffer3)
        sub.dispose()
    }

}

object Greeter {
    var greeterEnabled = true
    fun greet(name: String) {
        if (greeterEnabled)
            println("Hello, $name!")
    }
}

class TestCompanion {

    fun test() {
        println("Run test from instance")
    }

    companion object {
        fun testMe() {
            println("Run testMe from companion object")
        }
    }
}

fun main() {
    println("Config2 buffer size: " + Config2.buffer.size.toString())
    println("Config2 buffer2 size: " + Config2.buffer2.size.toString())
    println("Config2 buffer3 size: " + Config2.buffer3.size.toString())
    val first = Config.next()
    if (first != 1) error("FAIL Config.next first=$first")
    println("first: $first")
    val second = Config.next()
    if (second != 2) error("FAIL Config.next second=$second")
    println("second: $second")

    Greeter.greeterEnabled = false
    Greeter.greet("World")
    Greeter.greeterEnabled = true
    Greeter.greet("Kotlin")
    if (Greeter.greeterEnabled == false) error("FAIL Greeter.greeterEnabled should be true")

    val test = TestCompanion()
    TestCompanion.testMe()
    test.test()

    println("done")
}
