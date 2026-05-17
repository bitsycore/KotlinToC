package GenericsTest

// Generic function
fun <T> firstOf(list: List<T>): T { return list[0] }

// Generic class
class Wrapper<T>(private var value: T) {
    fun get(): T { return value }
    fun set(v: T) { value = v }
}

// Pair + to infix
fun main() {

    // Generic function
    val list = mutableListOf(10, 20, 30)
    defer list.dispose()
    println("firstOf = ${firstOf(list)}")
    if (firstOf(list) != 10) error("FAIL firstOf")

    // Generic class
    val w = Wrapper<String>("hello")
    println("Wrapper: ${w.get()}")
    if (w.get() != "hello") error("FAIL Wrapper initial")
    w.set("world")
    println("Wrapper after set: ${w.get()}")
    if (w.get() != "world") error("FAIL Wrapper set")

    // Pair
    val p = 1 to "one"
    println("Pair: ${p.first} -> ${p.second}")
    if (p.first != 1 || p.second != "one") error("FAIL Pair")

    // Triple
    val t = Triple(10, "hello", true)
    println("Triple: ${t.first}, ${t.second}, ${t.third}")
    if (t.first != 10 || t.second != "hello" || t.third != true) error("FAIL Triple")

    // Triple with type args
    val t2 = Triple<Boolean, Int, String>(false, 42, "world")
    println("Triple2: ${t2.first}, ${t2.second}, ${t2.third}")
    if (t2.first != false || t2.second != 42 || t2.third != "world") error("FAIL Triple2")

    // Pair with nullable type args
    val abc = Pair<Int?, String?>(null, "Hello world")
    println("abc.first is null: ${abc.first == null}")
    println("abc.second: ${abc.second}")
    if (abc.first != null) error("FAIL abc.first should be null")
    if (abc.second != "Hello world") error("FAIL abc.second")

    var abc2: Pair<Int?, String?>? = Pair<Int?, String?>(null, "Hello world")
    println("abc2 not null: ${abc2 != null}")
    if (abc2 == null) error("FAIL abc2 should not be null")
    abc2 = null
    println("abc2 is null: ${abc2 == null}")
    if (abc2 != null) error("FAIL abc2 should be null")

    println("done")
}
