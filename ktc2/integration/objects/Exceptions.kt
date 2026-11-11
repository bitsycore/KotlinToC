package objects

class ValidationError(val field: String, message: String) : Exception(message)

class ResourceError(message: String, cause: Throwable) : RuntimeException(message, cause)

class Resource(val name: String) {
	var open = true

	fun close() {
		open = false
		println("closed $name")
	}
}

fun parseAge(text: String): Int {
	val vAge = text.toIntOrNull() ?: throw ValidationError("age", "not a number: $text")
	if (vAge < 0) throw ValidationError("age", "negative: $vAge")
	return vAge
}

fun describe(text: String): String = try {
	"age ${parseAge(text)}"
} catch (vError: ValidationError) {
	"invalid ${vError.field} (${vError.message})"
}

fun divide(a: Int, b: Int): Int {
	require(b != 0) { "divisor must not be zero" }
	return a / b
}

fun firstPositive(values: IntArray): Int {
	for (vValue in values) {
		if (vValue > 0) return vValue
	}
	throw NoSuchElementException("no positive value")
}

fun withResource(name: String, fail: Boolean): String {
	val vResource = Resource(name)
	try {
		if (fail) throw IllegalStateException("$name failed")
		return "used $name"
	} finally {
		vResource.close()
	}
}

fun nested(depth: Int): Int {
	if (depth == 0) throw IllegalArgumentException("bottom")
	return nested(depth - 1) + 1
}

fun wrap(): Nothing {
	try {
		nested(3)
		error("unreachable")
	} catch (vCause: IllegalArgumentException) {
		throw ResourceError("wrapped", vCause)
	}
}

fun loopWithFinally(): Int {
	var vCount = 0
	for (vI in 0 until 5) {
		try {
			if (vI == 1) continue
			if (vI == 3) break
			vCount += 10
		} finally {
			vCount++
		}
	}
	return vCount
}

fun classify(action: Int): String {
	try {
		when (action) {
			0 -> throw IllegalArgumentException("argument")
			1 -> throw IllegalStateException("state")
			2 -> throw ValidationError("x", "validation")
			3 -> check(false) { "checked" }
			4 -> throw Error("an error")
		}
		return "fine"
	} catch (vError: IllegalArgumentException) {
		return "A:${vError.message}"
	} catch (vError: RuntimeException) {
		return "R:${vError.message}"
	} catch (vError: Exception) {
		return "E:${vError.message}"
	} catch (vError: Throwable) {
		return "T:${vError.message}"
	}
}

fun main() {
	println(describe("42"))
	println(describe("abc"))
	println(describe("-5"))

	try {
		println(divide(10, 2))
		println(divide(1, 0))
		println("not reached")
	} catch (vError: IllegalArgumentException) {
		println("caught: ${vError.message}")
	}

	println(firstPositive(intArrayOf(-1, 0, 7)))
	try {
		firstPositive(intArrayOf(-1))
	} catch (vError: NoSuchElementException) {
		println(vError)
	}

	// finally runs on return and on exception
	println(withResource("a", false))
	try {
		println(withResource("b", true))
	} catch (vError: IllegalStateException) {
		println("after finally: ${vError.message}")
	}

	// Propagation through several frames, cause chains
	try {
		wrap()
	} catch (vError: ResourceError) {
		println("${vError.message} <- ${vError.cause?.message}")
		println(vError.cause is IllegalArgumentException)
	}

	println(loopWithFinally())
	for (vAction in 0..5) println(classify(vAction))

	// try as an expression, rethrow, nested try
	val vNumber = try {
		"12x".toInt()
	} catch (vError: NumberFormatException) {
		-1
	}
	println(vNumber)
	try {
		try {
			throw IllegalStateException("inner")
		} catch (vError: IllegalArgumentException) {
			println("wrong handler")
		} finally {
			println("inner finally")
		}
	} catch (vError: IllegalStateException) {
		println("outer got ${vError.message}")
	}
	try {
		try {
			parseAge("x")
		} catch (vError: ValidationError) {
			println("rethrowing")
			throw vError
		}
	} catch (vError: Exception) {
		println(vError is ValidationError)
	}

	// Failures raised by the language itself
	val vNothing: String? = null
	try {
		println(vNothing!!.length)
	} catch (vError: NullPointerException) {
		println("null pointer")
	}
	val vAny: Any = "text"
	try {
		println((vAny as Resource).name)
	} catch (vError: ClassCastException) {
		println("bad cast")
	}
	try {
		listOf(1, 2).first { it > 5 }
	} catch (vError: NoSuchElementException) {
		println("no such element")
	}
	try {
		mutableListOf<Int>().removeAt(3)
	} catch (vError: IndexOutOfBoundsException) {
		println("list index")
	}
	println(Exception("plain").message)
	println(IllegalStateException("with name"))
	println(ValidationError("f", "custom").toString().endsWith("ValidationError: custom"))
	println("done")
}
