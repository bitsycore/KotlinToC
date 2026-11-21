package ktc2test.coroutines.suspendfuninterfaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.coroutines.*

// A `suspend fun interface` made from a lambda, a suspend lambda, a function reference and a suspend function
// reference (a reference to a plain function is adapted to the suspend member), called from a coroutine.

fun interface Print {
	suspend fun print(message: String): String
}

fun interface Combine {
	suspend fun combine(left: Int, right: Int): Int
}

fun plain(message: String): String = "plain $message"

suspend fun suspending(message: String): String = "suspending $message"

fun add(left: Int, right: Int): Int = left + right

/** Delegates to a Print made of a reference to a plain function, as `object : Print by Print(::id)`. */
object Delegated : Print by Print(::plain)

/** Runs [block] as a coroutine that completes at once, and gives its result. */
fun <T> complete(block: suspend () -> T): T {
	var vResult: Result<T>? = null
	block.startCoroutine(Continuation(EmptyCoroutineContext) { vResult = it })
	return vResult!!.getOrThrow()
}

class SuspendFunInterfacesTest {
	@Test
	fun fromLambdas() {
		val vLambda = Print { "lambda $it" }
		val vSuspendLambda = Print { message -> suspending(message) }
		assertEquals("lambda a", complete { vLambda.print("a") })
		assertEquals("suspending b", complete { vSuspendLambda.print("b") })
	}

	@Test
	fun fromReferences() {
		val vPlain = Print(::plain)
		val vSuspending = Print(::suspending)
		assertEquals("plain c", complete { vPlain.print("c") })
		assertEquals("suspending d", complete { vSuspending.print("d") })
		assertEquals(7, complete { Combine(::add).combine(3, 4) })
	}

	@Test
	fun byDelegation() {
		assertEquals("plain e", complete { Delegated.print("e") })
	}
}
