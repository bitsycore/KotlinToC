package TopVarWriteTest

// A top-level `var` written from inside a function must use the package-prefixed C name on the
// assignment LHS (it already does on reads). Pre-fix the LHS was a bare, undeclared name.

var gCount = 0
var gSum = 0

fun bump(n: Int) {
	gCount = gCount + 1
	gSum = gSum + n
}

fun main(): Int {
	bump(10)
	bump(20)
	bump(30)
	if (gCount != 3) {
		println("count FAIL: $gCount")
		return 1
	}
	if (gSum != 60) {
		println("sum FAIL: $gSum")
		return 1
	}
	println("TopVarWriteTest OK")
	return 0
}
