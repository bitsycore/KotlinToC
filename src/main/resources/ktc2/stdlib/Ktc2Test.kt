@file:Suppress("unused", "NOTHING_TO_INLINE")
@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package kotlin.test

import kotlin.contracts.contract

/*
`kotlin.test` for ktc2: the assertions of the Kotlin test library, so that a test written for
Kotlin Multiplatform's common tests compiles here as it is. Plain Kotlin, with nothing of a
platform in it: the suite compiles this same file for the JVM when it compares.
*/

// ==================
// MARK: Annotations
// ==================

/** Marks a function as a test. The runner the suite writes calls each of them. */
@Target(AnnotationTarget.FUNCTION)
public annotation class Test

/** Marks a test, or a class of tests, that is not run. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
public annotation class Ignore

/** Marks a function that runs before each test of its class. */
@Target(AnnotationTarget.FUNCTION)
public annotation class BeforeTest

/** Marks a function that runs after each test of its class. */
@Target(AnnotationTarget.FUNCTION)
public annotation class AfterTest

// ==================
// MARK: Assertions
// ==================

/** Fails the test with [message]. */
public fun fail(message: String? = null): Nothing = throw AssertionError(message ?: "Test failed")

/** Fails the test with [message] and [cause]. */
public fun fail(message: String? = null, cause: Throwable?): Nothing = throw AssertionError((message ?: "Test failed") + (cause?.let { ": $it" } ?: ""))

private fun prefix(message: String?): String = if (message == null) "" else "$message. "

public fun assertTrue(actual: Boolean, message: String? = null) {
	contract { returns() implies actual }
	if (!actual) fail(message ?: "Expected value to be true.")
}

public inline fun assertTrue(message: String? = null, block: () -> Boolean) {
	assertTrue(block(), message)
}

public fun assertFalse(actual: Boolean, message: String? = null) {
	contract { returns() implies !actual }
	if (actual) fail(message ?: "Expected value to be false.")
}

public inline fun assertFalse(message: String? = null, block: () -> Boolean) {
	assertFalse(block(), message)
}

public fun <T> assertEquals(expected: T, actual: T, message: String? = null) {
	if (expected != actual) fail(prefix(message) + "Expected <$expected>, actual <$actual>.")
}

public fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double, message: String? = null) {
	val vDifference = if (expected > actual) expected - actual else actual - expected
	// Equal as `equals` has it, so that NaN is what was expected when NaN was.
	if (!(vDifference <= absoluteTolerance) && expected.compareTo(actual) != 0) {
		fail(prefix(message) + "Expected <$expected> with absolute tolerance <$absoluteTolerance>, actual <$actual>.")
	}
}

public fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float, message: String? = null) {
	val vDifference = if (expected > actual) expected - actual else actual - expected
	// Equal as `equals` has it, so that NaN is what was expected when NaN was.
	if (!(vDifference <= absoluteTolerance) && expected.compareTo(actual) != 0) {
		fail(prefix(message) + "Expected <$expected> with absolute tolerance <$absoluteTolerance>, actual <$actual>.")
	}
}

public fun <T> assertNotEquals(illegal: T, actual: T, message: String? = null) {
	if (illegal == actual) fail(prefix(message) + "Illegal value: <$actual>.")
}

public fun <T> assertSame(expected: T, actual: T, message: String? = null) {
	if (expected !== actual) fail(prefix(message) + "Expected <$expected>, actual <$actual> is not same.")
}

public fun <T> assertNotSame(illegal: T, actual: T, message: String? = null) {
	if (illegal === actual) fail(prefix(message) + "Expected not same as <$actual>.")
}

public fun assertNull(actual: Any?, message: String? = null) {
	if (actual != null) fail(prefix(message) + "Expected value to be null, but was: <$actual>.")
}

public fun <T : Any> assertNotNull(actual: T?, message: String? = null): T {
	contract { returns() implies (actual != null) }
	if (actual == null) fail(prefix(message) + "Expected value to be not null.")
	return actual
}

public inline fun <T : Any, R> assertNotNull(actual: T?, message: String? = null, block: (T) -> R) {
	contract { returns() implies (actual != null) }
	block(assertNotNull(actual, message))
}

public inline fun <reified T> assertIs(value: Any?, message: String? = null): T {
	contract { returns() implies (value is T) }
	if (value !is T) fail((if (message == null) "" else "$message. ") + "Expected value to be of another type, actual <$value>.")
	return value
}

public inline fun <reified T> assertIsNot(value: Any?, message: String? = null) {
	contract { returns() implies (value !is T) }
	if (value is T) fail((if (message == null) "" else "$message. ") + "Expected value to not be of that type, actual <$value>.")
}

public fun <T> assertContains(iterable: Iterable<T>, element: T, message: String? = null) {
	if (!iterable.contains(element)) fail(prefix(message) + "Expected the collection to contain the element.\nCollection <$iterable>, element <$element>.")
}

public fun <T> assertContains(array: Array<T>, element: T, message: String? = null) {
	if (!array.contains(element)) fail(prefix(message) + "Expected the array to contain the element.\nElement <$element>.")
}

public fun <K, V> assertContains(map: Map<K, V>, key: K, message: String? = null) {
	if (!map.containsKey(key)) fail(prefix(message) + "Expected the map to contain the key.\nMap <$map>, key <$key>.")
}

public fun assertContains(charSequence: CharSequence, other: CharSequence, ignoreCase: Boolean = false, message: String? = null) {
	if (!charSequence.contains(other, ignoreCase)) fail(prefix(message) + "Expected the char sequence to contain the substring.\nCharSequence <$charSequence>, substring <$other>, ignoreCase <$ignoreCase>.")
}

public fun assertContains(charSequence: CharSequence, char: Char, ignoreCase: Boolean = false, message: String? = null) {
	if (!charSequence.contains(char, ignoreCase)) fail(prefix(message) + "Expected the char sequence to contain the char.\nCharSequence <$charSequence>, char <$char>, ignoreCase <$ignoreCase>.")
}

public fun assertContains(range: IntRange, value: Int, message: String? = null) {
	if (value !in range) fail(prefix(message) + "Expected the range <$range> to contain the value <$value>.")
}

public fun assertContains(range: LongRange, value: Long, message: String? = null) {
	if (value !in range) fail(prefix(message) + "Expected the range <$range> to contain the value <$value>.")
}

public fun assertContains(range: CharRange, value: Char, message: String? = null) {
	if (value !in range) fail(prefix(message) + "Expected the range <$range> to contain the value <$value>.")
}

public fun <T : Comparable<T>> assertContains(range: ClosedRange<T>, value: T, message: String? = null) {
	if (value !in range) fail(prefix(message) + "Expected the range <$range> to contain the value <$value>.")
}

public fun <T> assertContains(sequence: Sequence<T>, element: T, message: String? = null) {
	if (!sequence.contains(element)) fail(prefix(message) + "Expected the sequence to contain the element.\nSequence <$sequence>, element <$element>.")
}

public fun <T> assertContentEquals(expected: Iterable<T>?, actual: Iterable<T>?, message: String? = null) {
	if (expected == null || actual == null) {
		if (expected != null || actual != null) fail(prefix(message) + "Expected <$expected>, actual <$actual>.")
		return
	}
	val vExpected = expected.iterator()
	val vActual = actual.iterator()
	var vIndex = 0
	while (vExpected.hasNext() && vActual.hasNext()) {
		val vWanted = vExpected.next()
		val vGot = vActual.next()
		if (vWanted != vGot) fail(prefix(message) + "Iterable elements differ at index $vIndex. Expected element <$vWanted>, actual element <$vGot>.")
		vIndex++
	}
	if (vExpected.hasNext() || vActual.hasNext()) fail(prefix(message) + "Iterable lengths differ. Expected <$expected>, actual <$actual>.")
}

public fun <T> assertContentEquals(expected: Array<T>?, actual: Array<T>?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: IntArray?, actual: IntArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: LongArray?, actual: LongArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: DoubleArray?, actual: DoubleArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: ByteArray?, actual: ByteArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: CharArray?, actual: CharArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: BooleanArray?, actual: BooleanArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: ShortArray?, actual: ShortArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: FloatArray?, actual: FloatArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: UByteArray?, actual: UByteArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: UShortArray?, actual: UShortArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: UIntArray?, actual: UIntArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun assertContentEquals(expected: ULongArray?, actual: ULongArray?, message: String? = null) {
	assertContentEquals(expected?.asList(), actual?.asList(), message)
}

public fun <T> assertContentEquals(expected: Sequence<T>?, actual: Sequence<T>?, message: String? = null) {
	if (expected == null || actual == null) {
		if (expected != null || actual != null) fail(prefix(message) + "Expected <$expected>, actual <$actual>.")
		return
	}
	val vExpected = expected.iterator()
	val vActual = actual.iterator()
	var vIndex = 0
	while (vExpected.hasNext() && vActual.hasNext()) {
		val vWanted = vExpected.next()
		val vGot = vActual.next()
		if (vWanted != vGot) fail(prefix(message) + "Sequence elements differ at index $vIndex. Expected element <$vWanted>, actual element <$vGot>.")
		vIndex++
	}
	if (vExpected.hasNext() || vActual.hasNext()) fail(prefix(message) + "Sequence lengths differ.")
}

/** Checks that [block] gives [expected]. */
public inline fun <T> expect(expected: T, block: () -> T) {
	assertEquals(expected, block())
}

public inline fun <T> expect(expected: T, message: String?, block: () -> T) {
	assertEquals(expected, block(), message)
}

/** Checks that [block] fails, and gives what it threw. */
public inline fun assertFails(block: () -> Any?): Throwable = assertFails(null, block)

public inline fun assertFails(message: String?, block: () -> Any?): Throwable {
	try {
		block()
	} catch (inError: Throwable) {
		return inError
	}
	fail((if (message == null) "" else "$message. ") + "Expected an exception to be thrown, but was completed successfully.")
}

/** Checks that [block] fails with an exception of the class [exceptionClass], and gives it. */
public inline fun <T : Throwable> assertFailsWith(exceptionClass: kotlin.reflect.KClass<T>, block: () -> Any?): T =
	assertFailsWith(exceptionClass, null, block)

public inline fun <T : Throwable> assertFailsWith(exceptionClass: kotlin.reflect.KClass<T>, message: String?, block: () -> Any?): T {
	try {
		block()
	} catch (inError: Throwable) {
		@Suppress("UNCHECKED_CAST")
		if (exceptionClass.isInstance(inError)) return inError as T
		fail((if (message == null) "" else "$message. ") + "Expected an exception of another type to be thrown, but was $inError")
	}
	fail((if (message == null) "" else "$message. ") + "Expected an exception to be thrown, but was completed successfully.")
}

/** Checks that [block] fails with an exception of type [T], and gives it. */
public inline fun <reified T : Throwable> assertFailsWith(message: String? = null, block: () -> Any?): T {
	try {
		block()
	} catch (inError: Throwable) {
		if (inError is T) return inError
		fail((if (message == null) "" else "$message. ") + "Expected an exception of another type to be thrown, but was $inError")
	}
	fail((if (message == null) "" else "$message. ") + "Expected an exception to be thrown, but was completed successfully.")
}
