@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "UNUSED_PARAMETER")

package kotlin

/*
ktc2 platform layer: the exception classes of the standard library (on the JVM they are
typealiases to java.lang classes) and Comparator.
*/

public actual open class Error : Throwable {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class Exception : Throwable {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class RuntimeException : Exception {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class IllegalArgumentException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class IllegalStateException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class ConcurrentModificationException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class UnsupportedOperationException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class NoWhenBranchMatchedException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

public actual open class IndexOutOfBoundsException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
}

public actual open class NumberFormatException : IllegalArgumentException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
}

public actual open class NullPointerException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
}

public actual open class ClassCastException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
}

public actual open class NoSuchElementException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
}

public actual open class ArithmeticException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
}

public actual open class AssertionError : Error {
	public actual constructor() : super()
	public actual constructor(message: Any?) : super(message?.toString())
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

public actual class UninitializedPropertyAccessException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

/** ktc2 keeps no stack trace: this is the exception's own description. */
public actual fun Throwable.stackTraceToString(): String = toString()

public actual fun Throwable.printStackTrace() {
	println(toString())
}

public actual fun Throwable.addSuppressed(exception: Throwable) {
}

public actual val Throwable.suppressedExceptions: List<Throwable>
	get() = emptyList()

public actual fun interface Comparator<T> {
	public actual fun compare(a: T, b: T): Int
}
