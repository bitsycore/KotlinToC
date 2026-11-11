package basic

fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

fun gcd(a: Int, b: Int): Int {
	var vA = a
	var vB = b
	while (vB != 0) {
		val vRest = vA % vB
		vA = vB
		vB = vRest
	}
	return vA
}

fun classify(n: Int): Int = when {
	n < 0 -> -1
	n == 0 -> 0
	n < 10 -> 1
	else -> 2
}

fun weekday(d: Int): Int = when (d) {
	1, 7 -> 0
	2, 3, 4, 5, 6 -> 1
	else -> -1
}

fun noisy(tag: Int, result: Boolean): Boolean {
	println("eval $tag")
	return result
}

fun firstMultiple(of: Int, above: Int): Int {
	var vCandidate = above
	while (true) {
		vCandidate++
		if (vCandidate % of == 0) return vCandidate
	}
}

fun collatzSteps(start: Long): Int {
	var vValue = start
	var vSteps = 0
	do {
		vValue = if (vValue % 2 == 0L) vValue / 2 else vValue * 3 + 1
		vSteps++
	} while (vValue != 1L)
	return vSteps
}

fun sumTo(n: Int): Int {
	var vSum = 0
	for (vI in 1..n) vSum += vI
	return vSum
}

fun bump(x: Int): Int {
	println("bump $x")
	return x + 1
}

fun main() {
	println(fib(15))
	println(gcd(1071, 462))
	println("${classify(-5)} ${classify(0)} ${classify(7)} ${classify(70)}")
	println("${weekday(1)} ${weekday(4)} ${weekday(9)}")

	// Short-circuit evaluation keeps its order and skips the right side.
	println(noisy(1, false) && noisy(2, true))
	println(noisy(3, true) || noisy(4, true))
	if (noisy(5, true) && noisy(6, true)) println("both")

	// Operands are evaluated left to right, once.
	var vX = 1
	val vOrder = vX + bump(vX) * bump(10)
	println(vOrder)
	vX = 5
	val vPost = vX++ + vX
	val vPre = ++vX + vX
	println("$vPost $vPre $vX")

	println(firstMultiple(7, 30))
	println(collatzSteps(27L))
	println(sumTo(100))

	// Ranges
	for (vI in 0 until 3) print(vI)
	println()
	for (vI in 3 downTo 0) print(vI)
	println()
	for (vI in 2..<5) print(vI)
	println()
	for (vI in 5..4) print(vI)
	println("empty")
	for (vI in 2147483645..2147483647) print("$vI ")
	println()
	var vTotal = 0L
	for (vL in 1L..5L) vTotal += vL * vL
	println(vTotal)

	// Stepped ranges
	for (vI in 0 until 10 step 3) print("$vI ")
	println()
	for (vI in 0..10 step 5) print("$vI ")
	println()
	for (vI in 10 downTo 0 step 4) print("$vI ")
	println()
	for (vI in 1..<2 step 7) print("$vI ")
	println()
	for (vI in 5 until 5 step 2) print("$vI ")
	println("none")
	for (vI in 2147483640..2147483647 step 3) print("$vI ")
	println()
	for (vL in 100L downTo 90L step 4L) print("$vL ")
	println()
	val vStride = 2
	var vSteps = 0
	for (vI in bump(0)..bump(8) step bump(vStride)) vSteps += vI
	println(vSteps)

	// break / continue, including labeled ones across loops
	var vFound = 0
	outer@ for (vI in 1..9) {
		for (vJ in 1..9) {
			if (vJ > vI) continue@outer
			if (vI * vJ == 42) {
				vFound = vI * 10 + vJ
				break@outer
			}
		}
	}
	println(vFound)
	var vOdd = 0
	for (vI in 0 until 10) {
		if (vI % 2 == 0) continue
		if (vI > 7) break
		vOdd += vI
	}
	println(vOdd)
	var vN = 0
	do {
		vN++
		if (vN == 2) continue
		print(vN)
	} while (vN < 4)
	println()

	// if / when as expressions with side effects in the branches
	val vPicked = if (vOdd > 10) {
		println("big")
		vOdd * 2
	} else {
		println("small")
		vOdd
	}
	println(vPicked)
	val vLabel = when (val vRest = vPicked % 3) {
		0 -> 'a'
		1 -> 'b'
		else -> ('a' + vRest)
	}
	println(vLabel)
}
