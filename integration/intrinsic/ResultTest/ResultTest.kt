package ResultTest

fun divide(a: Int, b: Int): Result<Int> {
	if (b == 0) return Result<Int>(0, 1)
	return Result<Int>(a / b, 0)
}

fun safeSqrt(x: Float): Result<Float> {
	if (x < 0.0f) return Result<Float>(0.0f, 2)
	return Result<Float>(x, 0)
}

fun main() {
	// Basic success
	val ok = Result<Int>(42, 0)
	if (!ok.isSuccess) error("FAIL: should be success")
	if (ok.isFailure) error("FAIL: should not be failure")
	if (ok.value != 42) error("FAIL: value should be 42")
	if (ok.errorCode != 0) error("FAIL: errorCode should be 0")
	println("ok: ${ok.value}")

	// Basic failure
	val err = Result<Int>(0, 1)
	if (err.isSuccess) error("FAIL: should not be success")
	if (!err.isFailure) error("FAIL: should be failure")
	if (err.errorCode != 1) error("FAIL: errorCode should be 1")
	println("err: ${err.errorCode}")

	// getOrDefault via direct access
	val v1 = if (ok.isSuccess) ok.value else -1
	val v2 = if (err.isSuccess) err.value else -1
	if (v1 != 42) error("FAIL: getOrDefault on success")
	if (v2 != -1) error("FAIL: getOrDefault on failure")
	println("getOrDefault: $v1 $v2")

	// Function returning Result<Int>
	val d1 = divide(10, 2)
	val d2 = divide(10, 0)
	if (d1.value != 5) error("FAIL: divide success")
	if (!d2.isFailure) error("FAIL: divide by zero should fail")
	println("divide: ${d1.value} err=${d2.errorCode}")

	// Function returning Result<Float>
	val s1 = safeSqrt(4.0f)
	val s2 = safeSqrt(-1.0f)
	if (!s1.isSuccess) error("FAIL: safeSqrt positive")
	if (!s2.isFailure) error("FAIL: safeSqrt negative")
	println("sqrt: ${s1.value} err=${s2.errorCode}")

	// Multiple instantiations coexist
	val ri = Result<Int>(10, 0)
	val rf = Result<Float>(3.14f, 0)
	if (ri.value != 10) error("FAIL: Int result")
	if (rf.value < 3.13f || rf.value > 3.15f) error("FAIL: Float result")
	println("multi: ${ri.value} ${rf.value}")

	println("done")
}
