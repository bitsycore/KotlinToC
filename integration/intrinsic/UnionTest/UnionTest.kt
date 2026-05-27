package UnionTest

fun makeIntOrFloat(flag: Boolean): Union<Int, Float> {
	if (flag) return Union1<Int, Float>(42)
	return Union2<Int, Float>(3.14f)
}

fun describeUnion(u: Union<Int, Float>): String {
	if (u.is1()) return "int"
	return "float"
}

fun main() {
	// Basic construction and id check
	val u1 = Union1<Int, Float>(42)
	val u2 = Union2<Int, Float>(3.14f)
	if (u1.id != 0) error("FAIL: u1.id should be 0")
	if (u2.id != 1) error("FAIL: u2.id should be 1")
	println("ids: ${u1.id} ${u2.id}")

	// Value access
	val v1: Int = u1.get1()
	val v2: Float = u2.get2()
	if (v1 != 42) error("FAIL: u1.get1() should be 42")
	if (v2 < 3.13f || v2 > 3.15f) error("FAIL: u2.get2() should be ~3.14")
	println("values: $v1 $v2")

	// Boolean checks
	if (!u1.is1()) error("FAIL: u1.is1() should be true")
	if (u1.is2()) error("FAIL: u1.is2() should be false")
	if (u2.is1()) error("FAIL: u2.is1() should be false")
	if (!u2.is2()) error("FAIL: u2.is2() should be true")
	println("checks ok")

	// Function returning Union
	val r1 = makeIntOrFloat(true)
	val r2 = makeIntOrFloat(false)
	if (!r1.is1()) error("FAIL: r1 should be int")
	if (!r2.is2()) error("FAIL: r2 should be float")
	if (r1.get1() != 42) error("FAIL: r1 value")
	println("return: ${r1.get1()}")

	// Union as parameter
	if (describeUnion(u1) != "int") error("FAIL: describeUnion(u1)")
	if (describeUnion(u2) != "float") error("FAIL: describeUnion(u2)")
	println("param ok")

	// Three-member union
	val t1 = Union1<Int, Float, Boolean>(100)
	val t2 = Union2<Int, Float, Boolean>(2.5f)
	val t3 = Union3<Int, Float, Boolean>(true)
	if (t1.id != 0 || t2.id != 1 || t3.id != 2) error("FAIL: 3-member ids")
	if (t1.get1() != 100) error("FAIL: t1.get1()")
	if (t3.get3() != true) error("FAIL: t3.get3()")
	println("triple: ${t1.get1()} ${t2.get2()} ${t3.get3()}")

	// When-style dispatch
	val u = Union2<Int, Float>(9.0f)
	val result = if (u.is1()) {
		u.get1() * 10
	} else {
		u.get2().toInt() + 1
	}
	if (result != 10) error("FAIL: when dispatch result should be 10")
	println("dispatch: $result")

	println("done")
}
