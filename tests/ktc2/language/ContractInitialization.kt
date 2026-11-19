package ktc2test.language.contractinitialization

import kotlin.test.Test
import kotlin.contracts.*
import kotlin.coroutines.*

// A `val` given its value inside a lambda that a contract says runs exactly once (`callsInPlace`): the lambda is a
// closure like any other, not inlined, and the `val` is read after it ran, as kotlinx.coroutines' `coroutineScope { }`.

@OptIn(ExperimentalContracts::class)
fun once(block: () -> Unit) {
	contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
	block()
}

@OptIn(ExperimentalContracts::class)
fun <R> onceWith(block: () -> R): R {
	contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
	return block()
}

@OptIn(ExperimentalContracts::class)
suspend fun <R> suspending(block: suspend () -> R): R {
	contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
	return block()
}

/** Runs [block] as a coroutine that never suspends. */
fun runNow(block: suspend () -> Unit) {
	block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
}

@Test
fun main() {
	val a: Int
	once { a = 42 }
	println(a)

	val b: String
	once { b = "set" }
	println(b)

	val c: Long
	val d = onceWith {
		c = 7L
		"result"
	}
	println("$c $d")

	runNow {
		val e: Int
		suspending { e = 239 }
		println(e)
		val f: Int
		val g: Int
		suspending {
			f = 1
			g = 2
		}
		println(f + g)
	}
}
