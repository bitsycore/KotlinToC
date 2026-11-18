import kotlin.coroutines.*

// References to ordinary functions given where a `suspend` function type is taken (a suspend conversion), through
// generic code that calls them with its type parameters, as kotlinx.coroutines' `combine(other, String::plus)`.

suspend fun <A, B, R> apply2(a: A, b: B, transform: suspend (A, B) -> R): R = transform(a, b)

suspend fun <A, R> apply1(a: A, transform: suspend (A) -> R): R = transform(a)

fun twice(x: Int): Int = x * 2

fun joined(a: String, b: Int): String = "$a:$b"

class Box(val value: Int) {
	fun plus(other: Int): Int = value + other
}

/** Runs [block] as a coroutine that never suspends, and returns what it gave. */
fun <T> run(block: suspend () -> T): T {
	var vResult: Result<T>? = null
	block.startCoroutine(Continuation(EmptyCoroutineContext) { vResult = it })
	return vResult!!.getOrThrow()
}

fun main() {
	println(run { apply2("a", 1, String::plus) })
	println(run { apply2<String?, Int, String>(null, 2, String?::plus) })
	println(run { apply2("b", 3, ::joined) })
	println(run { apply1(21, ::twice) })
	println(run { apply1(4, Box(10)::plus) })
	println(run { apply2(Box(1), 5, Box::plus) })
	val vList = listOf(1, 2, 3)
	println(run { apply1(2, vList::get) })
}
