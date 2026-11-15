package kotlin

import kotlinx.cinterop.internal.cExpr

/*
ktc2 platform layer: `assert`, which the JVM and Kotlin/Native have outside common code. As on both,
assertions are off unless asked for: built with -DKTC2_ASSERTIONS (`-copt -DKTC2_ASSERTIONS`), as
the JVM runs with -ea and Kotlin/Native compiles with -enable-assertions. The condition is
evaluated either way; the message only when the assertion fails.
*/

/** True when the program was built with assertions on. */
@PublishedApi
internal fun ktc2AssertionsOn(): Boolean = cExpr("KTC2_ASSERTIONS_ON")

/** Throws an [AssertionError] if [value] is false, when assertions are on. */
public inline fun assert(value: Boolean) {
	assert(value) { "Assertion failed" }
}

/** Throws an [AssertionError] with the result of [lazyMessage] if [value] is false, when assertions are on. */
public inline fun assert(value: Boolean, lazyMessage: () -> Any) {
	if (ktc2AssertionsOn() && !value) throw AssertionError(lazyMessage())
}
