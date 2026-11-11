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

data class Color(val r: Int, val g: Int, val b: Int) {
	constructor(gray: Int) : this(gray, gray, gray)
	constructor() : this(0, 0, 0)
}

class Counter(var value: Int) {
	constructor() : this(0)
	constructor(start: Int, step: Int) : this(start + step)

	fun get(): Int = value
}

data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
	constructor(w: Float, h: Float) : this(0.0f, 0.0f, w, h)
	constructor(size: Float) : this(0.0f, 0.0f, size, size)

	fun area(): Float = w * h
}

fun main() {
	// Vec2 primary
	val a = Vec2(1.0f, 2.0f)
	println("Vec2 primary: ${a.x}, ${a.y}")
	if (a.x != 1.0f || a.y != 2.0f) error("FAIL primary ctor")

	// Vec2 single float → uniform
	val b = Vec2(3.0f)
	println("Vec2 single: ${b.x}, ${b.y}")
	if (b.x != 3.0f || b.y != 3.0f) error("FAIL single ctor")

	// Vec2 int conversion
	val c = Vec2(4, 5)
	println("Vec2 ints: ${c.x}, ${c.y}")
	if (c.x != 4.0f || c.y != 5.0f) error("FAIL int ctor")

	// Vec2 triple ints
	val d = Vec2(4, 5, 6)
	println("Vec2 triple ints: ${d.x}, ${d.y}")
	if (d.x != 20.0f || d.y != 30.0f) error("FAIL triple ctor")

	// Vec2 empty
	val e = Vec2()
	println("Vec2 empty: ${e.x}, ${e.y}")
	if (e.x != 0.0f || e.y != 0.0f) error("FAIL empty ctor")

	// Player primary + secondary
	val p1 = Player("Alice", 100)
	println("Player: ${p1.name}, ${p1.score}")
	if (p1.name != "Alice" || p1.score != 100) error("FAIL Player primary")

	val p2 = Player("Bob")
	println("Player default: ${p2.name}, ${p2.score}")
	if (p2.name != "Bob" || p2.score != 0) error("FAIL Player default")

	// Color
	val white = Color(255, 255, 255)
	if (white.r != 255 || white.g != 255 || white.b != 255) error("FAIL Color primary")
	println("Color primary: ${white.r},${white.g},${white.b}")

	val gray = Color(128)
	if (gray.r != 128 || gray.g != 128 || gray.b != 128) error("FAIL Color gray")
	println("Color gray: ${gray.r},${gray.g},${gray.b}")

	val black = Color()
	if (black.r != 0 || black.g != 0 || black.b != 0) error("FAIL Color empty")
	println("Color empty: ${black.r},${black.g},${black.b}")

	// Counter
	val c0 = Counter()
	if (c0.get() != 0) error("FAIL Counter empty: ${c0.get()}")

	val c1 = Counter(10)
	if (c1.get() != 10) error("FAIL Counter(10): ${c1.get()}")

	val c2 = Counter(10, 5)
	if (c2.get() != 15) error("FAIL Counter(10,5): ${c2.get()}")
	println("Counter: ${c0.get()}, ${c1.get()}, ${c2.get()}")

	// Rect
	val r1 = Rect(10.0f, 20.0f, 100.0f, 50.0f)
	if (r1.area() != 5000.0f) error("FAIL Rect primary area=${r1.area()}")

	val r2 = Rect(100.0f, 50.0f)
	if (r2.x != 0.0f || r2.y != 0.0f) error("FAIL Rect(w,h) origin")
	if (r2.area() != 5000.0f) error("FAIL Rect(w,h) area=${r2.area()}")

	val r3 = Rect(64.0f)
	if (r3.w != 64.0f || r3.h != 64.0f) error("FAIL Rect(size)")
	if (r3.area() != 4096.0f) error("FAIL Rect(size) area=${r3.area()}")
	println("Rect: area=${r1.area()}, ${r2.area()}, ${r3.area()}")

	// Data class equality across ctors
	val v1 = Vec2(5.0f, 5.0f)
	val v2 = Vec2(5.0f)
	if (v1 != v2) error("FAIL Vec2 equality across ctors")
	println("Vec2 equality across ctors: ok")

	println("ALL OK")
}
