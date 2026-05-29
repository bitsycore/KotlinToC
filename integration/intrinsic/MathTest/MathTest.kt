package MathTest

fun main(): Int {
	var ok = true

	// maxOf / minOf (Int then Double — tests type-correct overload selection)
	ok = ok && (maxOf(3, 7) == 7) && (minOf(3, 7) == 3)
	ok = ok && (maxOf(2.5, 1.5) == 2.5) && (minOf(2.5, 1.5) == 1.5)

	// abs (Int, Long, Double)
	ok = ok && (abs(-5) == 5) && (abs(5) == 5)
	ok = ok && (abs(-7L) == 7L)
	ok = ok && (abs(-2.0) == 2.0)

	// coerceIn / coerceAtLeast / coerceAtMost (Int)
	ok = ok && (10.coerceIn(0, 5) == 5)
	ok = ok && ((0 - 3).coerceIn(0, 5) == 0)
	ok = ok && (3.coerceIn(0, 5) == 3)
	ok = ok && (3.coerceAtLeast(5) == 5) && (9.coerceAtLeast(5) == 9)
	ok = ok && (9.coerceAtMost(5) == 5) && (2.coerceAtMost(5) == 2)

	// coerceIn (Float, Double)
	ok = ok && (5.0f.coerceIn(0.0f, 3.0f) == 3.0f)
	ok = ok && (1.5.coerceIn(2.0, 9.0) == 2.0)

	if (!ok) {
		println("MathTest FAIL")
		return 1
	}
	println("MathTest OK")
	return 0
}
