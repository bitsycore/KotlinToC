// What the box tests of `ranges` found:
// - a loop's step that is not positive ended the program, where Kotlin throws an IllegalArgumentException a `catch`
//   or `assertFailsWith` sees (`box/ranges/stepped/*/illegalStepNegative`);
// - a bound that jumps (`continue`) gave the loop a value of type void, which C refused (`box/ranges/kt37370a`);
// - `a..b` on a type parameter bounded by an integer (`T : Long`) was "not supported yet"
//   (`box/ranges/contains/smartCastOnBothEndsGeneric`);
// - a local named `NAN` was a C variable of that name, which `math.h` makes a macro (`box/ranges/contains/
//   inFloatingPointRangeWithNaNBound`): so is any name of the C headers' macros.

package ktc2test.regressions.rangeedgesfromboxtests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

fun stepOf(inStep: Int): Int = inStep

@Test
fun stepNotPositiveThrows() {
	val vError = assertFailsWith<IllegalArgumentException> {
		for (i in 1..8 step stepOf(-1)) {
			println(i)
		}
	}
	assertEquals("Step must be positive, was: -1.", vError.message)
	assertFailsWith<IllegalArgumentException> {
		for (i in 1L..<8L step 0L) {
		}
	}
	var vSum = 0
	for (i in 1..8 step 3) vSum += i
	assertEquals(12, vSum)
}

@Test
fun boundThatJumps() {
	var vCalls = 0
	fun next(): Int = ++vCalls
	for (i in 0..1) {
		for (j in next() downTo continue) {
		}
	}
	assertEquals(2, vCalls)
	for (i in 0..1) {
		for (j in continue downTo next()) {
		}
	}
	assertEquals(2, vCalls)
}

fun <T : Long> outsideLong(inLow: T?, inHigh: T?, inValue: T): Boolean = inLow != null && inHigh != null && inValue !in inLow..inHigh

fun <T : Int> outsideInt(inLow: T?, inHigh: T?, inValue: T): Boolean = inLow != null && inHigh != null && inValue !in inLow..inHigh

fun <T : UInt> insideUInt(inLow: T, inHigh: T, inValue: T): Boolean = inValue in inLow..<inHigh

@Test
fun rangeOfBoundedTypeParameter() {
	assertEquals(true, outsideLong(1L, 2L, 0L))
	assertEquals(false, outsideLong(1L, 2L, 2L))
	assertEquals(true, outsideInt(1, 2, 3))
	assertEquals(false, outsideInt(null, 2, 3))
	assertEquals(true, insideUInt(1u, 3u, 2u))
	assertEquals(false, insideUInt(1u, 3u, 3u))
}

class Limits(val EOF: Int, val INT_MAX: Long)

fun scaled(stdin: Int, errno: Int): Int = stdin * errno

@Test
fun namesOfCMacros() {
	val NAN = Double.NaN
	val INFINITY = 1.0
	assertEquals(false, NAN in 0.0..INFINITY)
	assertEquals(true, 0.5 in 0.0..INFINITY)
	val vLimits = Limits(-1, Long.MAX_VALUE)
	assertEquals(-1, vLimits.EOF)
	assertEquals(Long.MAX_VALUE, vLimits.INT_MAX)
	assertEquals(12, scaled(3, 4))
}
