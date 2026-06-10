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
	if (ok !is Result.Success) fatalError("FAIL: should be success")
	if (ok is Result.Failure) fatalError("FAIL: should not be failure")
	if (ok is Result.Success) {
		if (ok.value != 42) fatalError("FAIL: value should be 42")
		println("ok: ${ok.value}")
	}

	// Basic failure
	val err: Result<Int> = Result.Failure<Int>(1)
	if (err is Result.Success) fatalError("FAIL: should not be success")
	if (err !is Result.Failure) fatalError("FAIL: should be failure")
	if (err is Result.Failure) {
		if (err.errorCode != 1) fatalError("FAIL: errorCode should be 1")
		println("err: ${err.errorCode}")
	}

	// getOrDefault via smart-cast
	val v1 = if (ok is Result.Success) ok.value else -1
	val v2 = if (err is Result.Success) err.value else -1
	if (v1 != 42) fatalError("FAIL: getOrDefault on success")
	if (v2 != -1) fatalError("FAIL: getOrDefault on failure")
	println("getOrDefault: $v1 $v2")

	// Function returning Result<Int>
	val d1 = divide(10, 2)
	val d2 = divide(10, 0)
	if (d1 is Result.Success) {
		if (d1.value != 5) fatalError("FAIL: divide success")
		println("divide: ${d1.value}")
	}
	if (d2 is Result.Failure) {
		println("divide err: ${d2.errorCode}")
	}

	// Function returning Result<Float>
	val s1 = safeSqrt(4.0f)
	val s2 = safeSqrt(-1.0f)
	if (s1 !is Result.Success) fatalError("FAIL: safeSqrt positive")
	if (s2 !is Result.Failure) fatalError("FAIL: safeSqrt negative")
	println("sqrt ok")

	// Multiple instantiations coexist
	val ri: Result<Int> = Result.Success<Int>(10)
	val rf: Result<Float> = Result.Success<Float>(3.14f)
	if (ri is Result.Success && rf is Result.Success) {
		if (ri.value != 10) fatalError("FAIL: Int result")
		if (rf.value < 3.13f || rf.value > 3.15f) fatalError("FAIL: Float result")
		println("multi: ${ri.value} ${rf.value}")
	}

	// Companion factory methods
	val fc1 = Result.success<Int>(99)
	val fc2 = Result.failure<Int>(7)
	if (fc1 is Result.Success) {
		println("companion success: ${fc1.value}")
	} else {
		fatalError("FAIL: companion success")
	}
	if (fc2 is Result.Failure) {
		println("companion failure: ${fc2.errorCode}")
	} else {
		fatalError("FAIL: companion failure")
	}

	// isSuccess / isFailure inline extension properties
	if (!ok.isSuccess) fatalError("FAIL: ok.isSuccess")
	if (ok.isFailure) fatalError("FAIL: ok.isFailure should be false")
	if (err.isSuccess) fatalError("FAIL: err.isSuccess should be false")
	if (!err.isFailure) fatalError("FAIL: err.isFailure")
	println("isSuccess/isFailure: ok")

	// getOrNull / errorCodeOrNull extension functions
	val gn1 = ok.getOrNull()
	val gn2 = err.getOrNull()
	if (gn1 == null) fatalError("FAIL: ok.getOrNull should not be null")
	if (gn2 != null) fatalError("FAIL: err.getOrNull should be null")
	println("getOrNull: $gn1")

	val ec1 = ok.errorCodeOrNull()
	val ec2 = err.errorCodeOrNull()
	if (ec1 != null) fatalError("FAIL: ok.errorCodeOrNull should be null")
	if (ec2 == null) fatalError("FAIL: err.errorCodeOrNull should not be null")
	println("errorCodeOrNull: $ec2")

	println("done")
}
