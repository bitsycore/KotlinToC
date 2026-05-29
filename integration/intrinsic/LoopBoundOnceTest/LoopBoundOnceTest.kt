package LoopBoundOnceTest

// Proves range endpoints / steps are evaluated ONCE (Kotlin builds the progression once),
// not re-read every iteration. Pre-fix, the loop condition re-read the bound var each time, so
// mutating it in the body changed the iteration count. main returns non-zero on any mismatch.

fun main(): Int {
	// `until` upper bound: mutate it in the body — must still run the original 3 times.
	var n = 3
	var c1 = 0
	for (i in 0 until n) {
		c1 = c1 + 1
		n = 0
	}
	if (c1 != 3) {
		println("until-bound FAIL: ran $c1, expected 3")
		return 1
	}

	// `..` inclusive bound: same check (0..2 → 3 iterations).
	var m = 2
	var c2 = 0
	for (i in 0..m) {
		c2 = c2 + 1
		m = 0
	}
	if (c2 != 3) {
		println(".. bound FAIL: ran $c2, expected 3")
		return 1
	}

	// `downTo`: mutate the lower bound in the body — 5 downTo 1 → 5 iterations.
	var lo = 1
	var c3 = 0
	for (i in 5 downTo lo) {
		c3 = c3 + 1
		lo = 5
	}
	if (c3 != 5) {
		println("downTo-bound FAIL: ran $c3, expected 5")
		return 1
	}

	println("LoopBoundOnceTest OK")
	return 0
}
