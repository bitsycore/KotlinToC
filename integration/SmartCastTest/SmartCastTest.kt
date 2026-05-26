package SmartCastTest

data class Vec2(val x: Float, val y: Float)

fun describeNullable(v: Int?): Int {
	if (v == null) return -1
	if (v > 100) return 1
	return 0
}

fun main() {
	// Smart cast Int? after null check
	val x: Int? = 42
	if (x != null) {
		val doubled = x * 2
		if (doubled != 84) error("FAIL doubled: $doubled")
		println("doubled: $doubled")
	}

	// Else branch
	if (x == null) {
		println("is null")
	} else {
		println("value = $x")
	}

	// Smart cast String? after null check
	val s: String? = "hello"
	if (s != null) {
		if (s.length != 5) error("FAIL length: ${s.length}")
		println("length: ${s.length}")
	}

	// Null String
	val ns: String? = null
	if (ns != null) {
		error("FAIL ns should be null")
	} else {
		println("ns is null: ok")
	}

	// Array type checks
	val arr = intArrayOf(1, 2, 3)
	if (arr is Array<Int>) {
		println("arr is IntArray")
	} else {
		error("FAIL arr should be Array<Int>")
	}
	if (arr !is Array<Float>) {
		println("arr is not FloatArray")
	} else {
		error("FAIL arr should not be Array<Float>")
	}
	if (arr.size <= 0) error("FAIL arr.size=${arr.size}")
	println("has items: ${arr.size > 0}")

	// Nullable param in function
	val d1 = describeNullable(null)
	if (d1 != -1) error("FAIL describeNullable null: $d1")
	val d2 = describeNullable(50)
	if (d2 != 0) error("FAIL describeNullable 50: $d2")
	val d3 = describeNullable(200)
	if (d3 != 1) error("FAIL describeNullable 200: $d3")
	println("describeNullable: $d1, $d2, $d3")

	// Smart cast with arithmetic
	val a: Int? = 10
	val b: Int? = 20
	if (a != null && b != null) {
		val sum = a + b
		if (sum != 30) error("FAIL sum: $sum")
		println("sum: $sum")
	}

	// Nullable data class
	val v: Vec2? = Vec2(3.0f, 4.0f)
	if (v != null) {
		if (v.x != 3.0f) error("FAIL vec.x: ${v.x}")
		if (v.y != 4.0f) error("FAIL vec.y: ${v.y}")
		println("vec: ${v.x}, ${v.y}")
	}

	val nv: Vec2? = null
	if (nv != null) {
		error("FAIL nv should be null")
	}
	println("nv is null: ok")

	// Repeated calls to nullable function
	val r1 = describeNullable(null)
	val r2 = describeNullable(5)
	val r3 = describeNullable(999)
	if (r1 != -1) error("FAIL r1")
	if (r2 != 0) error("FAIL r2")
	if (r3 != 1) error("FAIL r3")
	println("repeated calls: $r1, $r2, $r3")

	// Float nullable
	val f: Float? = 3.14f
	if (f != null) {
		val doubled = f * 2.0f
		println("float doubled: $doubled")
	}

	println("ALL OK")
}
