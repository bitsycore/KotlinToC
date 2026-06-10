package NullabilityTest

fun maybeInt(n: Int): Int? {
	if (n > 0) {
		return n
	}
	return null
}

fun maybeString(s: String): String? {
	if (s.length > 0) return s
	return null
}

data class Vec2(val x: Float, val y: Float)

fun maybeVec(x: Float): Vec2? {
	if (x > 0.0f) return Vec2(x, x * 2.0f)
	return null
}

fun firstNonNull(a: Int?, b: Int?, fallback: Int): Int {
	if (a != null) return a
	if (b != null) return b
	return fallback
}

fun main() {
	// Basic null/non-null
	val x: Int? = null
	println("null == null = ${x == null}")
	if (x != null) fatalError("FAIL x should be null")

	val y: Int? = 42
	println("42 != null = ${y != null}")
	if (y == null) fatalError("FAIL y should not be null")

	// Elvis operator
	val v = x ?: 99
	println("elvis null: $v")
	if (v != 99) fatalError("FAIL elvis null: $v")

	val v2 = y ?: 99
	println("elvis non-null: $v2")
	if (v2 != 42) fatalError("FAIL elvis non-null: $v2")

	// Nullable function returns
	val a = maybeInt(10)
	if (a != null) {
		if (a != 10) fatalError("FAIL maybeInt(10): $a")
		println("maybeInt(10) = $a")
	} else {
		fatalError("FAIL maybeInt(10) is null")
	}

	val b = maybeInt(0)
	if (b != null) fatalError("FAIL maybeInt(0) should be null")
	println("maybeInt(0) = null")

	val neg = maybeInt(-5)
	if (neg != null) fatalError("FAIL maybeInt(-5) should be null")
	println("maybeInt(-5) = null")

	// Nullable string
	val s: String? = "hello"
	if (s != null) {
		if (s.length != 5) fatalError("FAIL string length")
		println("length = ${s.length}")
	} else {
		fatalError("FAIL s is null")
	}

	val emptyResult = maybeString("")
	if (emptyResult != null) fatalError("FAIL maybeString empty should be null")
	println("maybeString empty = null")

	val nonEmpty = maybeString("world")
	if (nonEmpty == null) fatalError("FAIL maybeString world is null")
	if (nonEmpty != "world") fatalError("FAIL maybeString world: $nonEmpty")
	println("maybeString world = $nonEmpty")

	// Nullable data class
	val vec = maybeVec(3.0f)
	if (vec == null) fatalError("FAIL maybeVec(3) is null")
	if (vec != null) {
		if (vec.x != 3.0f || vec.y != 6.0f) fatalError("FAIL maybeVec values: $vec")
		println("maybeVec(3): $vec")
	}

	val noVec = maybeVec(-1.0f)
	if (noVec != null) fatalError("FAIL maybeVec(-1) should be null")
	println("maybeVec(-1) = null")

	// Elvis on data class
	val safeVec = noVec ?: Vec2(0.0f, 0.0f)
	if (safeVec.x != 0.0f || safeVec.y != 0.0f) fatalError("FAIL elvis vec: $safeVec")
	println("elvis vec: $safeVec")

	// Chained nullability
	val first = firstNonNull(null, null, 0)
	if (first != 0) fatalError("FAIL firstNonNull all null: $first")
	val second = firstNonNull(null, 5, 0)
	if (second != 5) fatalError("FAIL firstNonNull b=5: $second")
	val third = firstNonNull(3, 5, 0)
	if (third != 3) fatalError("FAIL firstNonNull a=3: $third")
	println("firstNonNull: $first, $second, $third")

	// Nullable comparison
	val n1: Int? = 10
	val n2: Int? = 10
	val n3: Int? = 20
	val n4: Int? = null
	if (n1 != n2) fatalError("FAIL nullable eq")
	if (n1 == n3) fatalError("FAIL nullable neq")
	if (n1 == n4) fatalError("FAIL nullable vs null")
	if (n4 != null) fatalError("FAIL null != null")
	println("nullable comparisons: ok")

	println("ALL OK")
}
