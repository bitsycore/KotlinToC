// printStackTrace() writes to the standard error, on a Throwable and on a class of the program
// that extends one; what is printed to the standard output is not mixed with it.

package ktc2test.objects.printstacktrace

import kotlin.test.Test

class AppException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Test
fun main() {
	println("before")
	try {
		throw AppException("outer", IllegalStateException("inner"))
	} catch (inError: AppException) {
		inError.printStackTrace()
		println("caught ${inError.message}")
	}
	val vPlain: Throwable = IllegalArgumentException("plain")
	vPlain.printStackTrace()
	println(vPlain.stackTraceToString().lines().first())
	println("after")
}
