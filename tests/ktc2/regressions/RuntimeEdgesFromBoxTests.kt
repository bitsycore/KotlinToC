// What the Kotlin compiler's box tests found at run time (tests/box/): a `finally` that throws after a `return` ran the
// `finally` blocks again; an unchecked cast to a type parameter let through what its caller did not expect, and let a
// null through a `T : Any`; a lambda that captures nothing was a new object each time; an enum's companion object was
// made apart from its entries; an enum entry with named arguments in another order was not made; a local class
// created inside a local function did not have what it captures.

package ktc2test.regressions.runtimeedgesfromboxtests

import kotlin.test.Test

class Trace(var text: String)

fun returnThroughThrowingFinally(trace: Trace): String {
	try {
		try {
			trace.text += " try"
			return trace.text
		} finally {
			trace.text += " inner"
		}
	} finally {
		trace.text += " outer"
		throw IllegalStateException("from finally")
	}
}

@Suppress("UNCHECKED_CAST")
fun <T> unchecked(value: Any?): T = value as T

@Suppress("UNCHECKED_CAST")
fun <T : Any> notNull(value: Any?): Boolean = try {
	value as T
	true
} catch (e: NullPointerException) {
	false
}

fun noCapture(): () -> Unit = {}

var gLog = ""

enum class Level(val label: String) {
	LOW("low"),
	HIGH("high");

	init {
		gLog += "$label;"
	}

	companion object {
		init {
			gLog += "companion;"
		}

		fun first() = LOW
	}
}

enum class Pair2(val x: String, val y: String) {
	ONE(y = "y", x = "x")
}

fun localClassInLocalFunction(text: String): String {
	class Holder {
		val held = text
	}
	fun make() = Holder()
	return make().held
}

@Test
fun main() {
	val vTrace = Trace("start")
	try {
		returnThroughThrowingFinally(vTrace)
	} catch (e: IllegalStateException) {
		println(e.message)
	}
	println(vTrace.text)

	try {
		val vText: String = unchecked(Any())
		println("no exception: ${vText.length}")
	} catch (e: ClassCastException) {
		println("ClassCastException")
	}
	println(unchecked<String>("text").length)
	println(notNull<String>("x"))
	println(notNull<String>(null))

	println(noCapture() === noCapture())

	println(Level.first().label)
	println(gLog)
	println(Pair2.ONE.x + Pair2.ONE.y)
	println(localClassInLocalFunction("local"))
}
