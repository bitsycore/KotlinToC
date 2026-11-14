import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

// `suspend` functions run by hand: started with `startCoroutine`, suspended with
// `suspendCoroutine`, resumed later from a list of waiting continuations.

val waiting = ArrayList<Continuation<Int>>()

suspend fun direct(x: Int): Int = x * 2

suspend fun later(x: Int): Int = suspendCoroutine { continuation ->
	println("waits for $x")
	waiting += continuation
}

suspend fun now(x: Int): Int = suspendCoroutine { continuation -> continuation.resume(x + 1) }

suspend fun sum(n: Int): Int {
	var total = 0
	for (i in 1..n) {
		total += later(i)
		println("total so far $total")
	}
	return total
}

suspend fun text(prefix: String): String {
	val parts = ArrayList<String>()
	parts += prefix
	parts += later(1).toString()
	parts += now(2).toString()
	parts += direct(3).toString()
	return parts.joinToString("-")
}

suspend fun failing(x: Int): Int {
	val y = later(x)
	if (y > 10) throw IllegalStateException("too big: $y")
	return y
}

suspend fun guarded(x: Int): String {
	try {
		return "got ${failing(x)}"
	} catch (e: IllegalStateException) {
		return "caught ${e.message}"
	} finally {
		println("finally for $x")
	}
}

suspend fun nothing() {
	println("nothing runs")
}

suspend fun unitLater() {
	later(0)
	println("unitLater resumed")
}

class Counter(var count: Int) {
	suspend fun step(by: Int): Int {
		count += now(by)
		return count
	}
}

fun <T> run(name: String, block: suspend () -> T) {
	block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
		println("$name ended with $result")
	})
}

fun resumeAll(value: Int) {
	while (waiting.isNotEmpty()) {
		val next = waiting.removeAt(0)
		next.resume(value)
	}
}

fun main() {
	run("direct") { direct(21) }
	run("now") { now(1) + now(2) }
	run("sum") { sum(3) }
	resumeAll(10)
	run("text") { text("a") }
	resumeAll(7)
	run("guarded small") { guarded(1) }
	resumeAll(5)
	run("guarded big") { guarded(2) }
	resumeAll(50)
	run("nothing") { nothing() }
	run("unitLater") { unitLater() }
	resumeAll(0)
	val counter = Counter(1)
	run("counter") { counter.step(1) + counter.step(2) }
	println("count ${counter.count}")
	run("context") { coroutineContext === EmptyCoroutineContext }
	var captured = 5
	run("captured") {
		captured += later(1)
		captured
	}
	resumeAll(3)
	println("captured $captured")
	run("exception") { failing(1) }
	resumeAll(99)
	val wrapped = suspend { "wrapped" }
	run("value") { wrapped() + wrapped() }
	val withParam: suspend (Int) -> Int = { it + later(it) }
	run("param") { withParam(4) }
	resumeAll(1)
	val raw = suspend { 42 }.createCoroutine(Continuation(EmptyCoroutineContext) { println("created ended with $it") })
	println("created, not started")
	raw.resume(Unit)
	val returned = suspend { later(9) }.startCoroutineUninterceptedOrReturn(Continuation(EmptyCoroutineContext) { println("unintercepted ended with $it") })
	println("unintercepted returned suspended: ${returned === COROUTINE_SUSPENDED}")
	resumeAll(8)
}
