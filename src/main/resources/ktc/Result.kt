package ktc

/**
 * Lightweight Result<T> - Success carries the value, Failure carries the
 * [Throwable] that produced it (Kotlin semantics). Build one directly, or
 * capture exceptions with [runCatching]:
 *
 *     val r = runCatching { "42".toInt() }
 *     if (r.isSuccess) println(r.getOrNull())
 *     r.exceptionOrNull()?.let { println(it.message) }
 */
@MustUseReturnValue
@SimpleUnion
sealed interface Result<T> {
	data class Success<T>(val value: T) : Result<T>
	class Failure<T>(val exception: Throwable) : Result<T>

	companion object {
		inline fun <T> success(value: T): Result<T> = Result.Success(value)
		inline fun <T> failure(exception: Throwable): Result<T> = Result.Failure<T>(exception)
	}

}

inline val <T> Result<T>.isFailure get() = this is Result.Failure

inline val <T> Result<T>.isSuccess get() = this is Result.Success

/** The value, or null when this is a [Result.Failure]. */
inline fun <T> Result<T>.getOrNull(): T? {
	if (this is Result.Success) return this.value
	return null
}

/** The captured exception, or null when this is a [Result.Success]. */
inline fun <T> Result<T>.exceptionOrNull(): Throwable? {
	if (this is Result.Failure) return this.exception
	return null
}

/** The value, or [defaultValue] when this is a [Result.Failure]. */
inline fun <T> Result<T>.getOrDefault(defaultValue: T): T {
	if (this is Result.Success) return this.value
	return defaultValue
}

/** The value - or rethrows the captured exception when this is a [Result.Failure]. */
inline fun <T> Result<T>.getOrThrow(): T {
	if (this is Result.Failure) throw this.exception
	return (this as Result.Success).value
}

/**
 * Runs [block] and captures its outcome: [Result.Success] with the returned
 * value, or [Result.Failure] with the [Throwable] it threw.
 */
inline fun <T> runCatching(block: () -> T): Result<T> {
	try {
		return Result.Success(block())
	} catch (e: Throwable) {
		return Result.Failure<T>(e)
	}
}
