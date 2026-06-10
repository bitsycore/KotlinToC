package AnyDeepTest

fun deep1(item: Any): Int? {
    if (item is Int)    { return item * 2 }
    if (item is String) { return item.length }
    if (item is Boolean){ return if(item) 1 else 0 }
    return null
}

fun deepAsQuestion(item: Any): Int? {
    val i: Int?    = (item as? Int)
    val s: String? = (item as? String)
    if (i == null && s == null) return null
    if (i != null) return i
    return s?.length
}

fun checkNullable(item: Any?) {
    if (item == null)  { println("null!") }
    else if (item is Int)    { println("int: $item") }
    else if (item is String) { println("str: $item") }
}

fun main() {
    val r1 = deep1(42)
    if (r1 != null) {
        if (r1 != 84) fatalError("FAIL deep1(42): $r1")
        println(r1)
    } else { fatalError("FAIL deep1(42) is null") }

    val r2 = deep1("hello")
    if (r2 != null) {
        if (r2 != 5) fatalError("FAIL deep1(hello): $r2")
        println(r2)
    } else { fatalError("FAIL deep1(hello) is null") }

    val r3 = deep1(true)
    if (r3 != null) {
        if (r3 != 1) fatalError("FAIL deep1(true): $r3")
        println(r3)
    } else { fatalError("FAIL deep1(true) is null") }

    val q1 = deepAsQuestion(99)
    if (q1 != null) {
        if (q1 != 99) fatalError("FAIL deepAsQ(99): $q1")
        println(q1)
    } else { fatalError("FAIL deepAsQ(99) is null") }

    val q2 = deepAsQuestion("test")
    if (q2 != null) {
        if (q2 != 4) fatalError("FAIL deepAsQ(test): $q2")
        println(q2)
    } else { fatalError("FAIL deepAsQ(test) is null") }

    val q3 = deepAsQuestion(1.0f)
    if (q3 != null) fatalError("FAIL deepAsQ(float): $q3")
    println(q3)

    checkNullable(42)
    checkNullable(null)

    val a: Any? = 42
    val b: Any? = null
    if (a != null) { println("a: not null") } else { fatalError("FAIL a null") }
    if (b == null) { println("b is null") } else { fatalError("FAIL b not null") }

    val c: Any? = "hello"
    checkNullable(c)

    val x: Any = "example"
    if (x is String) { println("is string: $x") } else { fatalError("FAIL is String") }
    if (x !is Int) { println("not int") } else { fatalError("FAIL not int") }

    println("ALL OK")
}
