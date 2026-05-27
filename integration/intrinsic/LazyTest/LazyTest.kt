package lazytest

// ── Top-level lazy val ──

var initCount = 0

val greeting: String by lazy {
	initCount++
	"Hello, Lazy World!"
}

// ── Top-level lazy with inferred type ──

val magicNumber by lazy { 42 }

// ── Top-level lazy with multi-statement body ──

val computed: Int by lazy {
	val a = 10
	val b = 32
	a + b
}

// ── Tests ──

fun testLocalLazy() {
	var called = 0
	val x: Int by lazy {
		called++
		100
	}
	if (called != 0) error("FAIL: lazy init ran before access")
	if (x != 100)    error("FAIL: lazy value wrong")
	if (called != 1) error("FAIL: lazy init didn't run on first access")
	val y = x + x
	if (called != 1) error("FAIL: lazy init ran more than once")
	if (y != 200)    error("FAIL: lazy value not reusable")
	println("  localLazy: OK")
}

fun testLocalLazyInferred() {
	val msg by lazy { "inferred" }
	if (msg != "inferred") error("FAIL: inferred lazy value wrong")
	println("  localLazyInferred: OK")
}

fun testLocalLazyMultiStmt() {
	val result: Int by lazy {
		val base = 5
		val factor = 7
		base * factor
	}
	if (result != 35) error("FAIL: multi-statement lazy body wrong")
	println("  localLazyMultiStmt: OK")
}

fun testTopLevelLazy() {
	val prevCount = initCount
	val g = greeting
	if (g != "Hello, Lazy World!") error("FAIL: top-level lazy value wrong")
	if (initCount != prevCount + 1) error("FAIL: top-level lazy init count wrong")
	val g2 = greeting
	if (initCount != prevCount + 1) error("FAIL: top-level lazy init ran more than once")
	println("  topLevelLazy: OK")
}

fun testTopLevelInferred() {
	if (magicNumber != 42) error("FAIL: top-level inferred lazy wrong")
	println("  topLevelInferred: OK")
}

fun testTopLevelMultiStmt() {
	if (computed != 42) error("FAIL: top-level multi-stmt lazy wrong")
	println("  topLevelMultiStmt: OK")
}

fun main() {
	println("LazyTest")
	testLocalLazy()
	testLocalLazyInferred()
	testLocalLazyMultiStmt()
	testTopLevelLazy()
	testTopLevelInferred()
	testTopLevelMultiStmt()
	println("All tests passed")
}
