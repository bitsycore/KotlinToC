package ktc

sealed interface Result<T> {
	data class Success<T>(val value: T) : Result<T>
	data class Failure<T>(val errorCode: Int) : Result<T>
}
