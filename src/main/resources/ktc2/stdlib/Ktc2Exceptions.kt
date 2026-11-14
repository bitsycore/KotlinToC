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

/** As on the JVM and on Kotlin/Native, where the standard library does not declare it for every platform. */
public open class OutOfMemoryError : Error {
	public constructor() : super()
	public constructor(message: String?) : super(message)
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
	/** As on the JVM and on Kotlin/Native: a message that is an exception is also the cause. */
	public actual constructor(message: Any?) : super(message?.toString(), message as? Throwable)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

public actual class UninitializedPropertyAccessException : RuntimeException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)
	public actual constructor(message: String?, cause: Throwable?) : super(message, cause)
	public actual constructor(cause: Throwable?) : super(cause)
}

/**
 * ktc2 keeps no stack trace: this is the exception's own description, then those of its
 * suppressed exceptions and of its cause, laid out as the JVM lays out a trace without the
 * lines of the stack. An exception met a second time is named and not gone into again.
 */
public actual fun Throwable.stackTraceToString(): String {
	val vText = StringBuilder()
	describeTo(vText, "", "", ArrayList())
	return vText.toString()
}

private fun Throwable.describeTo(text: StringBuilder, indent: String, caption: String, seen: ArrayList<Throwable>) {
	text.append(indent).append(caption)
	if (seen.any { it === this }) {
		text.append("[CIRCULAR REFERENCE: ").append(toString()).append("]\n")
		return
	}
	seen.add(this)
	text.append(toString()).append('\n')
	for (vSuppressed in suppressedExceptions) vSuppressed.describeTo(text, indent + "\t", "Suppressed: ", seen)
	cause?.describeTo(text, indent, "Caused by: ", seen)
}

public actual fun Throwable.printStackTrace() {
	println(toString())
}

public actual fun Throwable.addSuppressed(exception: Throwable) {
	if (exception === this) return
	@Suppress("UNCHECKED_CAST")
	var vList = ktc2.internal.throwableSuppressed(this) as ArrayList<Throwable>?
	if (vList == null) {
		vList = ArrayList()
		ktc2.internal.setThrowableSuppressed(this, vList)
	}
	vList.add(exception)
}

public actual val Throwable.suppressedExceptions: List<Throwable>
	@Suppress("UNCHECKED_CAST")
	get() = (ktc2.internal.throwableSuppressed(this) as ArrayList<Throwable>?) ?: emptyList()

public actual fun interface Comparator<T> {
	public actual fun compare(a: T, b: T): Int
}
