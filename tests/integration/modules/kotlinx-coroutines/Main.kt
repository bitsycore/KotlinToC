import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*

suspend fun compute(n: Int): Int {
	delay(1)
	return n * n
}

fun main() = runBlocking {
	val a = async { delay(10); 20 }
	val b = async { delay(5); 22 }
	println("sum ${a.await() + b.await()}")
	launch { println("launched") }.join()

	// Channels
	val channel = Channel<Int>()
	launch { for (i in 1..5) channel.send(i * 10); channel.close() }
	var total = 0
	for (v in channel) total += v
	println("channel $total")

	// Flow
	val squares = flow { for (i in 1..5) emit(compute(i)) }.map { it + 1 }.filter { it % 2 == 0 }.toList()
	println("flow $squares")

	// Dispatchers.Default and a mutex
	val mutex = Mutex()
	var counter = 0
	coroutineScope {
		repeat(100) {
			launch(Dispatchers.Default) { mutex.withLock { counter++ } }
		}
	}
	println("counter $counter")

	// Cancellation and timeouts
	val job = launch { try { delay(10_000) } finally { println("cancelled cleanly") } }
	delay(10)
	job.cancelAndJoin()
	val late = withTimeoutOrNull(20) { delay(1_000); "late" }
	println("timeout $late")

	// Exceptions
	try {
		coroutineScope { launch { throw IllegalStateException("boom") } }
	} catch (e: IllegalStateException) {
		println("caught ${e.message}")
	}
	println("done")
}
