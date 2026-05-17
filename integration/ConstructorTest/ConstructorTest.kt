package ConstructorTest

data class Vec2(val x: Float, val y: Float) {
    constructor(s: Float) : this(s, s)
    constructor(x: Int, y: Int) : this(x.toFloat(), y.toFloat())
    constructor(x: Int, y: Int, z: Int) : this(x.toFloat() * y.toFloat(), y.toFloat() * z.toFloat())
    constructor() : this(0.0f, 0.0f) {
        println("Empty constructor")
    }
}

class Player(val name: String, var score: Int) {
    constructor(name: String) : this(name, 0)
}

fun main() {
    val a = Vec2(1.0f, 2.0f)
    println("Vec2 primary: ${a.x}, ${a.y}")
    if (a.x != 1.0f || a.y != 2.0f) error("FAIL primary ctor")

    val b = Vec2(3.0f)
    println("Vec2 single: ${b.x}, ${b.y}")
    if (b.x != 3.0f || b.y != 3.0f) error("FAIL single ctor")

    val c = Vec2(4, 5)
    println("Vec2 ints: ${c.x}, ${c.y}")
    if (c.x != 4.0f || c.y != 5.0f) error("FAIL int ctor")

    val d = Vec2(4, 5, 6)
    println("Vec2 triple ints: ${d.x}, ${d.y}")
    if (d.x != 20.0f || d.y != 30.0f) error("FAIL triple ctor")

    val e = Vec2()
    println("Vec2 empty: ${e.x}, ${e.y}")
    if (e.x != 0.0f || e.y != 0.0f) error("FAIL empty ctor")

    val p1 = Player("Alice", 100)
    println("Player: ${p1.name}, ${p1.score}")
    if (p1.name != "Alice" || p1.score != 100) error("FAIL Player primary")

    val p2 = Player("Bob")
    println("Player default: ${p2.name}, ${p2.score}")
    if (p2.name != "Bob" || p2.score != 0) error("FAIL Player default")

    println("ALL OK")
}
