package tailrectest

// ── Basic tailrec: factorial with accumulator ──

tailrec fun factorial(n: Int, acc: Int = 1): Int {
	if (n <= 1) return acc
	return factorial(n - 1, n * acc)
}

// ── Two-param tailrec: greatest common divisor ──

tailrec fun gcd(a: Int, b: Int): Int {
	if (b == 0) return a
	return gcd(b, a % b)
}

// ── Tailrec with multiple branches ──

tailrec fun collatz(n: Int, steps: Int = 0): Int {
	if (n <= 1) return steps
	if (n % 2 == 0) return collatz(n / 2, steps + 1)
	return collatz(3 * n + 1, steps + 1)
}

// ── Tailrec extension function ──

tailrec fun Int.sumDownTo(acc: Int = 0): Int {
	if (this <= 0) return acc
	return (this - 1).sumDownTo(acc + this)
}

// ── Tailrec with named arguments ──

tailrec fun power(base: Int, exp: Int, acc: Int = 1): Int {
	if (exp <= 0) return acc
	return power(base = base, exp = exp - 1, acc = acc * base)
}

// ── Deep recursion (would overflow stack without tailrec) ──

tailrec fun countDown(n: Int): Int {
	if (n <= 0) return 0
	return countDown(n - 1)
}

// ── Tests ──

fun testFactorial() {
	if (factorial(0) != 1)       fatalError("FAIL factorial(0)")
	if (factorial(1) != 1)       fatalError("FAIL factorial(1)")
	if (factorial(5) != 120)     fatalError("FAIL factorial(5)")
	if (factorial(10) != 3628800) fatalError("FAIL factorial(10)")
	println("  factorial: OK")
}

fun testGcd() {
	if (gcd(12, 8) != 4)   fatalError("FAIL gcd(12,8)")
	if (gcd(100, 75) != 25) fatalError("FAIL gcd(100,75)")
	if (gcd(7, 13) != 1)   fatalError("FAIL gcd(7,13)")
	if (gcd(0, 5) != 5)    fatalError("FAIL gcd(0,5)")
	if (gcd(17, 17) != 17) fatalError("FAIL gcd(17,17)")
	println("  gcd: OK")
}

fun testCollatz() {
	if (collatz(1) != 0)   fatalError("FAIL collatz(1)")
	if (collatz(2) != 1)   fatalError("FAIL collatz(2)")
	if (collatz(6) != 8)   fatalError("FAIL collatz(6)")
	if (collatz(27) != 111) fatalError("FAIL collatz(27)")
	println("  collatz: OK")
}

fun testExtensionTailrec() {
	if (0.sumDownTo() != 0)  fatalError("FAIL 0.sumDownTo()")
	if (1.sumDownTo() != 1)  fatalError("FAIL 1.sumDownTo()")
	if (5.sumDownTo() != 15) fatalError("FAIL 5.sumDownTo()")
	if (10.sumDownTo() != 55) fatalError("FAIL 10.sumDownTo()")
	println("  extensionTailrec: OK")
}

fun testNamedArgs() {
	if (power(2, 0) != 1)    fatalError("FAIL power(2,0)")
	if (power(2, 10) != 1024) fatalError("FAIL power(2,10)")
	if (power(3, 5) != 243)  fatalError("FAIL power(3,5)")
	println("  namedArgs: OK")
}

fun testDeepRecursion() {
	if (countDown(100000) != 0) fatalError("FAIL countDown(100000)")
	println("  deepRecursion (100k): OK")
}

fun main() {
	println("TailrecTest")
	testFactorial()
	testGcd()
	testCollatz()
	testExtensionTailrec()
	testNamedArgs()
	testDeepRecursion()
	println("All tests passed")
}
