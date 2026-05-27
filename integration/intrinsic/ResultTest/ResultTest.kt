package ResultTest

fun divide(a: Int, b: Int): Result<Int> {
	if (b == 0) return Result.Failure<Int>(1)
	return Result.Success<Int>(a / b)
}

fun safeSqrt(x: Float): Result<Float> {
	if (x < 0.0f) return Result.Failure<Float>(2)
	return Result.Success<Float>(x)
}

fun main() {
	// Basic success
	val ok: Result<Int> = Result.Success<Int>(42)
	if (ok !is Result.Success) error("FAIL: should be success")
	if (ok is Result.Failure) error("FAIL: should not be failure")
	if (ok is Result.Success) {
		if (ok.value != 42) error("FAIL: value should be 42")
		println("ok: ${ok.value}")
	}

	// Basic failure
	val err: Result<Int> = Result.Failure<Int>(1)
	if (err is Result.Success) error("FAIL: should not be success")
	if (err !is Result.Failure) error("FAIL: should be failure")
	if (err is Result.Failure) {
		if (err.errorCode != 1) error("FAIL: errorCode should be 1")
		println("err: ${err.errorCode}")
	}

	// getOrDefault via smart-cast
	val v1 = if (ok is Result.Success) ok.value else -1
	val v2 = if (err is Result.Success) err.value else -1
	if (v1 != 42) error("FAIL: getOrDefault on success")
	if (v2 != -1) error("FAIL: getOrDefault on failure")
	println("getOrDefault: $v1 $v2")

	// Function returning Result<Int>
	val d1 = divide(10, 2)
	val d2 = divide(10, 0)
	if (d1 is Result.Success) {
		if (d1.value != 5) error("FAIL: divide success")
		println("divide: ${d1.value}")
	}
	if (d2 is Result.Failure) {
		println("divide err: ${d2.errorCode}")
	}

	// Function returning Result<Float>
	val s1 = safeSqrt(4.0f)
	val s2 = safeSqrt(-1.0f)
	if (s1 !is Result.Success) error("FAIL: safeSqrt positive")
	if (s2 !is Result.Failure) error("FAIL: safeSqrt negative")
	println("sqrt ok")

	// Multiple instantiations coexist
	val ri: Result<Int> = Result.Success<Int>(10)
	val rf: Result<Float> = Result.Success<Float>(3.14f)
	if (ri is Result.Success && rf is Result.Success) {
		if (ri.value != 10) error("FAIL: Int result")
		if (rf.value < 3.13f || rf.value > 3.15f) error("FAIL: Float result")
		println("multi: ${ri.value} ${rf.value}")
	}

	println("done")
}
