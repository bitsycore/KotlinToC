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

public actual fun readln(): String = throw UnsupportedOperationException("readln")

public actual fun readlnOrNull(): String? = null

internal actual interface Serializable
