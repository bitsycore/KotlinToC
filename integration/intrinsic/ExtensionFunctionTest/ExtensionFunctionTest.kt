package ExtensionFunctionTest

class Box(var value: Int)

fun Box.double(): Int { return value * 2 }
fun Box.tripled(): Int { return value * 3 }
fun Box.isPositive(): Boolean = value > 0

fun Int.clampPositive(): Int = if (this < 0) 0 else this
fun Int.squared(): Int = this * this
fun Float.approxEquals(other: Float): Boolean = (this - other) < 0.001f && (other - this) < 0.001f

data class Vec2(val x: Float, val y: Float)

fun Vec2.lengthSq(): Float = x * x + y * y
fun Vec2.scale(factor: Float): Vec2 = Vec2(x * factor, y * factor)
fun Vec2.add(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
fun Vec2.dot(other: Vec2): Float = x * other.x + y * other.y
fun Vec2.negate(): Vec2 = Vec2(-x, -y)

fun main() {
	// Box extensions (read-only, since extensions get a copy of the receiver)
	val b = Box(21)
	val d = b.double()
	if (d != 42) error("FAIL double: $d")
	println("Box double: $d")

	val t = b.tripled()
	if (t != 63) error("FAIL tripled: $t")
	println("Box tripled: $t")

	if (!b.isPositive()) error("FAIL isPositive")
	val bz = Box(0)
	if (bz.isPositive()) error("FAIL isPositive zero")
	val bn = Box(-5)
	if (bn.isPositive()) error("FAIL isPositive neg")
	println("isPositive: ok")

	// Primitive extensions
	val neg = (-5).clampPositive()
	if (neg != 0) error("FAIL clampPositive neg: $neg")
	val pos = 7.clampPositive()
	if (pos != 7) error("FAIL clampPositive pos: $pos")
	val zero = 0.clampPositive()
	if (zero != 0) error("FAIL clampPositive zero: $zero")
	println("clampPositive: $neg, $pos, $zero")

	val sq = 6.squared()
	if (sq != 36) error("FAIL squared: $sq")
	val sq0 = 0.squared()
	if (sq0 != 0) error("FAIL squared 0: $sq0")
	println("squared: $sq, $sq0")

	val eq = 3.14f.approxEquals(3.14f)
	if (!eq) error("FAIL approxEquals")
	val neq = 3.14f.approxEquals(3.2f)
	if (neq) error("FAIL approxEquals should differ")
	println("approxEquals: $eq, $neq")

	// Data class extensions
	val v1 = Vec2(3.0f, 4.0f)
	val len = v1.lengthSq()
	if (len != 25.0f) error("FAIL lengthSq: $len")
	println("lengthSq: $len")

	val v2 = v1.scale(2.0f)
	if (v2.x != 6.0f || v2.y != 8.0f) error("FAIL scale: $v2")
	println("scale: $v2")

	val v3 = v1.add(Vec2(1.0f, 1.0f))
	if (v3.x != 4.0f || v3.y != 5.0f) error("FAIL add: $v3")
	println("add: $v3")

	val dp = v1.dot(Vec2(1.0f, 0.0f))
	if (dp != 3.0f) error("FAIL dot: $dp")
	println("dot: $dp")

	val v4 = v1.negate()
	if (v4.x != -3.0f || v4.y != -4.0f) error("FAIL negate: $v4")
	println("negate: $v4")

	// Chained extensions
	val v5 = Vec2(1.0f, 0.0f).scale(5.0f).add(Vec2(0.0f, 3.0f))
	if (v5.x != 5.0f || v5.y != 3.0f) error("FAIL chained: $v5")
	println("chained: $v5")

	// Extension on zero vec
	val vz = Vec2(0.0f, 0.0f)
	if (vz.lengthSq() != 0.0f) error("FAIL zero lengthSq")
	println("zero lengthSq: ${vz.lengthSq()}")

	println("ALL OK")
}
