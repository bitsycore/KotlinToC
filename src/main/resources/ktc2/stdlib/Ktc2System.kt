@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package kotlin.system

import ktc2.internal.ticksNanos
import ktc2.internal.timeNanos

/*
kotlin.system: timing a block. The clock is the one of the day, in nanoseconds since 1970;
setting that clock while a block runs shows in the result.
*/

/** Runs [block] and returns how long it took, in milliseconds. */
public inline fun measureTimeMillis(block: () -> Unit): Long {
	val vStart = ticksNanos()
	block()
	return (ticksNanos() - vStart) / 1_000_000L
}

/**
 * Ends the program at once with [status] as its exit code, as `System.exit` on the JVM and `exit`
 * of the C library on Kotlin/Native do: what was printed is written out, nothing else runs.
 */
public fun exitProcess(status: Int): Nothing {
	ktc2.internal.exitProcess(status)
	throw IllegalStateException("exitProcess returned")
}

/** The time of the system's clock, in milliseconds since 1970 (Kotlin/Native's `getTimeMillis`). */
public fun getTimeMillis(): Long = timeNanos() / 1_000_000L

/** The time of the system's clock, in microseconds since 1970. */
public fun getTimeMicros(): Long = timeNanos() / 1_000L

/** The time of the system's clock, in nanoseconds since 1970. */
public fun getTimeNanos(): Long = timeNanos()

/** Runs [block] and returns how long it took, in nanoseconds. */
public inline fun measureNanoTime(block: () -> Unit): Long {
	val vStart = ticksNanos()
	block()
	return ticksNanos() - vStart
}
