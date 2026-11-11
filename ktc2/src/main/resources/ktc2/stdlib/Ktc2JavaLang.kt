@file:Suppress("PackageDirectoryMismatch")

package java.lang

// ==================
// MARK: System
// ==================

/** The part of `java.lang.System` that is about time. */
public object System {
	/** Milliseconds since 1970. */
	@JvmStatic
	public fun currentTimeMillis(): kotlin.Long = ktc2.internal.timeNanos() / 1_000_000L

	/** A count of nanoseconds for measuring elapsed time. It follows the clock of the day: setting that clock moves it. */
	@JvmStatic
	public fun nanoTime(): kotlin.Long = ktc2.internal.timeNanos()
}

// ==================
// MARK: Exceptions of java.lang
// ==================

/*
The exceptions Kotlin code names without a Kotlin declaration behind them: on the JVM they
are the classes of java.lang, here they are these.
*/

public open class ArrayIndexOutOfBoundsException : kotlin.IndexOutOfBoundsException {
	public constructor() : super()
	public constructor(message: kotlin.String?) : super(message)
}

public open class StringIndexOutOfBoundsException : kotlin.IndexOutOfBoundsException {
	public constructor() : super()
	public constructor(message: kotlin.String?) : super(message)
}
