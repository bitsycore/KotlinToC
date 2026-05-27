package ktc

@SimpleUnion
sealed interface Result<T> {
	data class Success<T>(val value: T) : Result<T>
	data class Failure<T>(val errorCode: Int) : Result<T>

	companion object {
		inline fun <T> success(value: T): Result<T> = Result.Success(value)
		inline fun <T> failure(errorCode: Int): Result<T> = Result.Failure(errorCode)
	}

}

inline val <T> Result<T>.isFailure get() = this is Result.Failure

inline val <T> Result<T>.isSuccess get() = this is Result.Success

inline fun <T> Result<T>.getOrNull(): T? {
	if (this is Result.Success) return this.value
	return null
}

inline fun <T> Result<T>.errorCodeOrNull(): Int? {
	if (this is Result.Failure) return this.errorCode
	return null
}