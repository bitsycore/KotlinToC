package DisposeTest

// Class without explicit dispose — gets implicit no-op
class SimpleData(val x: Int, val y: Int)

// Class with explicit dispose override
class ManagedResource {
    var id: Int = 0

    override fun dispose() {
        println("disposed resource $id")
    }
}

// Generic class without explicit dispose
class Box<T>(private var value: T) {
    fun get(): T = value
}

fun main() {
    // Test 1: implicit dispose does nothing (no crash)
    val s = SimpleData(1, 2)
    s.dispose()
    println("implicit dispose ok")

    // Test 2: defer on implicit dispose
    val s2 = SimpleData(3, 4)
    defer s2.dispose()
    println("defer on implicit dispose ok")

    // Test 3: explicit dispose
    val r = ManagedResource()
    r.id = 42
    r.dispose()

    // Test 4: defer on explicit dispose
    val r2 = ManagedResource()
    r2.id = 99
    defer r2.dispose()
    println("before end of scope")

    // Test 5: generic class implicit dispose
    val b = Box<String>("hello")
    b.dispose()
    println("generic implicit dispose ok")

    // Test 6: mutableListOf has dispose (from Disposable interface)
    val list = mutableListOf(1, 2, 3)
    defer list.dispose()
    println("list dispose ok")

    // Test 7: HashMap has dispose (from Disposable interface)
    val map = HashMap<Int, String>(4)
    map[1] = "one"
    defer map.tryDispose()
    println("map dispose ok")

    println("done")
}

fun Map<K, V>.tryDispose() {
    println("Trying to dispose a map of size = $size")
    dispose()
}
