package FunRefTest

// Regression test for B10: a function reference (`::name`) must produce a correctly-typed C
// function pointer, not `void (*f)(void)`. main returns a non-zero exit code if any check fails.

fun add(a: Int, b: Int): Int = a + b

fun negate(x: Int): Int = -x

fun scale(x: Double): Double = x * 2.0

fun main(): Int {
	var vOk = true

	// Two-arg Int->Int reference, stored and called.
	val f = ::add
	vOk = vOk && f(2, 3) == 5
	vOk = vOk && f(10, 7) == 17

	// One-arg reference.
	val g = ::negate
	vOk = vOk && g(4) == -4

	// Reassignable var of function-pointer type (same signature).
	var h = ::add
	h = ::add
	vOk = vOk && h(1, 1) == 2

	// Non-Int signature (Double->Double).
	val s = ::scale
	vOk = vOk && s(2.5) == 5.0

	if (!vOk) {
		println("FunRefTest FAILED")
		return 1
	}
	println("FunRefTest OK")
	return 0
}
