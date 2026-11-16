// The exceptions the JVM has and Kotlin/Native does not let a program name: ArrayIndexOutOfBoundsException
// is deprecated there with an error, StringIndexOutOfBoundsException and NegativeArraySizeException do not
// exist. The runtime still throws an ArrayIndexOutOfBoundsException, which a program catches as an
// IndexOutOfBoundsException.

fun main() {
	try {
		println(intArrayOf(1)[3])
	} catch (vError: ArrayIndexOutOfBoundsException) {
		println("array")
	}
	try {
		println("a"[3])
	} catch (vError: StringIndexOutOfBoundsException) {
		println("string")
	}
	val vError: Throwable = NegativeArraySizeException("size")
	println(vError)
}
