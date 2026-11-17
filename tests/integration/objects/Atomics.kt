@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package objects.atomics

import kotlin.concurrent.atomics.*
import ktc2.concurrent.thread

/** `kotlin.concurrent.atomics` changed by several threads at once: no update is lost. */
class Node(val label: String, val next: Node?)

fun main() {
	val counter = AtomicInt(0)
	val total = AtomicLong(0L)
	val cells = AtomicIntArray(4)
	val flag = AtomicBoolean(false)
	val stack = AtomicReference<Node?>(null)
	val slots = AtomicArray<String?>(4) { null }
	val workers = (0 until 4).map { vWorker ->
		thread {
			repeat(10_000) { vStep ->
				counter.incrementAndFetch()
				total.addAndFetch(vStep.toLong())
				cells.fetchAndAddAt(vStep % 4, 1)
				counter.update { it + 1 }
			}
			flag.compareAndSet(false, true)
			// A list pushed onto from every thread: a node lost to a race would be missing at the end.
			repeat(100) { vStep ->
				stack.update { Node("w$vWorker-$vStep", it) }
			}
			slots.compareAndSetAt(vWorker, null, "worker $vWorker")
		}
	}
	workers.forEach { it.join() }
	println(counter.load())
	println(total.load())
	println(cells)
	println(flag)
	var vCount = 0
	var vNode = stack.load()
	while (vNode != null) {
		vCount++
		vNode = vNode.next
	}
	println(vCount)
	println(slots)
	println(AtomicInt(5).compareAndExchange(5, 7))
	println(AtomicLongArray(longArrayOf(1L, 2L)).exchangeAt(1, 9L))
	println(AtomicReference("a").exchange("b"))
}
