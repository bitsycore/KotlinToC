package ktc

/**
 * A discriminated union that encapsulates a successful outcome with a value of type [T]
 * or a failure with an integer error code.
 *
 * In KTC's no-exception model, fallible functions return Result<T> instead of throwing.
 * Error code 0 means success; any non-zero value means failure.
 *
 * Usage:
 *   fun divide(a: Int, b: Int): Result<Int> {
 *       if (b == 0) return Result<Int>(0, 1)
 *       return Result<Int>(a / b, 0)
 *   }
 *   val r = divide(10, 2)
 *   if (r.isSuccess) println(r.value)
 */
data class Result<T>(
	val value: T,
	val errorCode: Int
) {
	val isSuccess: Boolean
		get() = errorCode == 0

	val isFailure: Boolean
		get() = errorCode != 0
}
