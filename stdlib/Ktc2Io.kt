@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNUSED_PARAMETER")

package kotlin.io

/*
ktc2 platform layer for kotlin.io.

ktc2 lowers calls to print / println directly, so these bodies are never translated; they
exist because the common standard library declares the functions as `expect`.
*/

public actual fun println() {
}

public actual fun println(message: Any?) {
}

public actual fun print(message: Any?) {
}

/** The next line of the standard input, without its line end. */
public actual fun readln(): String = readlnOrNull() ?: throw ReadAfterEOFException("EOF has already been reached")

/** The next line of the standard input, without its line end; null when there is none left. */
public actual fun readlnOrNull(): String? {
	val vBytes = ktc2.internal.readLineBytes() ?: return null
	return ktc2.internal.stringOfUtf8(vBytes, 0, vBytes.size)
}

public fun readLine(): String? = readlnOrNull()

internal actual interface Serializable
