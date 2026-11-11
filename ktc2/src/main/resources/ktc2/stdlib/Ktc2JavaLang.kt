@file:Suppress("PackageDirectoryMismatch")

package java.lang

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
