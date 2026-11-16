@file:Suppress("ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.coroutines.cancellation

/*
ktc2 platform layer: the exception a cancelled coroutine ends with. On the JVM it is the JDK's
`java.util.concurrent.CancellationException`; here, as on Kotlin/Native, a class of the library.
*/

public actual open class CancellationException : IllegalStateException {
	public actual constructor() : super()
	public actual constructor(message: String?) : super(message)

	/** For the functions `CancellationException(message, cause)` and `CancellationException(cause)`, which have the signatures a constructor would. */
	internal constructor(message: String?, cause: Throwable?, @Suppress("UNUSED_PARAMETER") inMarker: Boolean) : super(message, cause)
}

public actual fun CancellationException(message: String?, cause: Throwable?): CancellationException = CancellationException(message, cause, true)

public actual fun CancellationException(cause: Throwable?): CancellationException = CancellationException(cause?.toString(), cause, true)
