@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package kotlin.system

import ktc2.internal.timeNanos

/*
kotlin.system: timing a block. The clock is the one of the day, in nanoseconds since 1970;
setting that clock while a block runs shows in the result.
*/

/** Runs [block] and returns how long it took, in milliseconds. */
public inline fun measureTimeMillis(block: () -> Unit): Long {
	val vStart = timeNanos()
	block()
	return (timeNanos() - vStart) / 1_000_000L
}

/** Runs [block] and returns how long it took, in nanoseconds. */
public inline fun measureNanoTime(block: () -> Unit): Long {
	val vStart = timeNanos()
	block()
	return timeNanos() - vStart
}
