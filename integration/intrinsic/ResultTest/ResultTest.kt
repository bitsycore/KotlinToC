package ResultTest

fun divide(a: Int, b: Int): Result<Int> {
	if (b == 0) return Result.Failure<Int>(IllegalArgumentException("division by zero"))
	return Result.Success<Int>(a / b)
}

fun safeSqrt(x: Float): Result<Float> {
	if (x < 0.0f) return Result.Failure<Float>(IllegalArgumentException("negative"))
	return Result.Success<Float>(x)
}

// Throwing helper for the runCatching/getOrThrow tests.
fun failingInt(): Int {
	error("captured")
}

// Unit-returning helper — exercises Result<Unit> (Unit lowers to the ktc_Unit
// value in value positions; plain Unit returns stay C void).
var gSideEffects = 0
fun unitWork(): Unit {
	gSideEffects = gSideEffects + 1
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

	// Basic failure — carries the Throwable that produced it
	val err: Result<Int> = Result.Failure<Int>(RuntimeException("boom"))
	if (err is Result.Success) fatalError("FAIL: should not be success")
	if (err !is Result.Failure) fatalError("FAIL: should be failure")
	if (err is Result.Failure) {
		if (err.exception.message != "boom") fatalError("FAIL: exception message")
		println("err: ${err.exception.message}")
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
		println("divide err: ${d2.exception.message}")
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
	val fc2 = Result.failure<Int>(IllegalStateException("nope"))
	if (fc1 is Result.Success) {
		println("companion success: ${fc1.value}")
	} else {
		fatalError("FAIL: companion success")
	}
	if (fc2 is Result.Failure) {
		println("companion failure: ${fc2.exception.message}")
	} else {
		fatalError("FAIL: companion failure")
	}

	// isSuccess / isFailure inline extension properties
	if (!ok.isSuccess) fatalError("FAIL: ok.isSuccess")
	if (ok.isFailure) fatalError("FAIL: ok.isFailure should be false")
	if (err.isSuccess) fatalError("FAIL: err.isSuccess should be false")
	if (!err.isFailure) fatalError("FAIL: err.isFailure")
	println("isSuccess/isFailure: ok")

	// getOrNull / exceptionOrNull extension functions
	val gn1 = ok.getOrNull()
	val gn2 = err.getOrNull()
	if (gn1 == null) fatalError("FAIL: ok.getOrNull should not be null")
	if (gn2 != null) fatalError("FAIL: err.getOrNull should be null")
	println("getOrNull: $gn1")

	val ex1 = ok.exceptionOrNull()
	val ex2 = err.exceptionOrNull()
	if (ex1 != null) fatalError("FAIL: ok.exceptionOrNull should be null")
	if (ex2 == null) fatalError("FAIL: err.exceptionOrNull should not be null")
	println("exceptionOrNull: ok")

	// getOrDefault extension
	if (ok.getOrDefault(-5) != 42) fatalError("FAIL: getOrDefault success")
	if (err.getOrDefault(-5) != -5) fatalError("FAIL: getOrDefault failure")
	println("getOrDefault ext: ok")

	// runCatching — success path
	val rc1 = runCatching { 6 * 7 }
	if (rc1 !is Result.Success) fatalError("FAIL: runCatching success")
	if (rc1.getOrDefault(0) != 42) fatalError("FAIL: runCatching value")
	println("runCatching success: ok")

	// runCatching — a thrown exception lands in Failure
	val rc2 = runCatching { "not a number".toInt() }
	if (rc2 !is Result.Failure) fatalError("FAIL: runCatching failure")
	val rcEx = rc2.exceptionOrNull()
	if (rcEx == null) fatalError("FAIL: runCatching exception missing")
	println("runCatching failure: ${rcEx.message}")

	// getOrThrow — rethrows the captured exception, catchable by its class
	if (runCatching { 5 }.getOrThrow() != 5) fatalError("FAIL: getOrThrow success")
	var caught = 0
	try {
		val unused = runCatching { failingInt() }.getOrThrow()
		fatalError("FAIL: getOrThrow should have thrown $unused")
	} catch (e: IllegalStateException) {
		if (e.message != "captured") fatalError("FAIL: getOrThrow message")
		caught = 1
	}
	if (caught != 1) fatalError("FAIL: getOrThrow not caught")
	println("getOrThrow: ok")

	// Result<Unit> — runCatching over Unit blocks
	val rcu = runCatching { unitWork() }
	if (rcu !is Result.Success) fatalError("FAIL Result<Unit> success")
	if (gSideEffects != 1) fatalError("FAIL unit block ran $gSideEffects times")
	val rfu = runCatching { error("unit boom") }
	if (rfu !is Result.Failure) fatalError("FAIL Result<Unit> failure")
	val rfEx = rfu.exceptionOrNull()
	if (rfEx == null) fatalError("FAIL Result<Unit> exceptionOrNull")
	if (rfEx.message != "unit boom") fatalError("FAIL Result<Unit> message")
	var unitCaught = 0
	try {
		rfu.getOrThrow()
		fatalError("FAIL Result<Unit> getOrThrow should throw")
	} catch (e: IllegalStateException) {
		unitCaught = 1
	}
	if (unitCaught != 1) fatalError("FAIL Result<Unit> getOrThrow")
	println("Result<Unit>: ok")

	println("done")
}
