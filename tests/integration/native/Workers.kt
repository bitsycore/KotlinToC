// Kotlin/Native's workers, which kotlinx.coroutines runs its dispatchers on. Native only: compared with
// Kotlin/Native's output (Workers.expected, from Kotlin/Native 2.5.0-RC).
@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.native.concurrent.*
import kotlin.native.identityHashCode

fun main() {
	val worker = Worker.start(name = "w1")
	println(worker.name)
	println(worker == Worker.current)
	val future = worker.execute(TransferMode.SAFE, { 20 }) { it * 2 + 2 }
	println(future.result)
	println(future.state)

	// Jobs run in order; a delayed one after those that are due.
	val order = kotlin.concurrent.AtomicReference("")
	worker.executeAfter(30_000) { order.value = order.value + "c" }
	worker.executeAfter { order.value = order.value + "a" }
	worker.executeAfter { order.value = order.value + "b" }
	worker.requestTermination().result
	println(order.value)

	// The main thread's worker processes its queue when it parks.
	val main = Worker.current
	println(main == Worker.current)
	var ran = 0
	main.executeAfter { ran++ }
	println(main.park(0, process = true))
	println(ran)
	println(main.park(1_000, process = true))
	main.executeAfter(1_000) { ran += 10 }
	while (ran < 11) main.park(100_000, process = true)
	println(ran)

	// Several workers at once.
	val workers = List(3) { Worker.start(name = "pool-$it") }
	val futures = workers.mapIndexed { index, each -> each.execute(TransferMode.SAFE, { index }) { (it + 1) * 100 } }
	println(futures.map { it.result })
	workers.forEach { it.requestTermination().result }

	println(kotlin.native.Platform.getAvailableProcessors() > 0)
	val any = Any()
	println(any.identityHashCode() == any.identityHashCode())
	println(null.identityHashCode())
}
