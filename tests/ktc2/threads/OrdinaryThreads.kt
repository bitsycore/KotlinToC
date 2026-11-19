@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package ktc2test.threads.ordinarythreads

import kotlin.test.Test
import kotlin.concurrent.atomics.*
import ktc2.concurrent.Thread
import ktc2.concurrent.sleep
import ktc2.concurrent.synchronized
import ktc2.concurrent.thread

// Threads as ordinary code uses them: workers that fill their own part of a result, a shared
// counter under a lock and as an atomic, `lazy` reached from several threads, an object
// initialized by whichever thread comes first, a queue handed from producers to a consumer,
// and threads that start other threads. What is printed does not depend on the order.

object Registry {
	val created = AtomicInt(0)
	val names: List<String>

	init {
		created.incrementAndFetch()
		names = listOf("a", "b", "c")
	}
}

class Cache {
	val initializations = AtomicInt(0)
	val table: Map<Int, String> by lazy {
		initializations.incrementAndFetch()
		(1..100).associateWith { "v$it" }
	}
}

class BoundedQueue<T>(private val capacity: Int) {
	private val items = ArrayDeque<T>()

	fun offer(item: T): Boolean = synchronized(this) {
		if (items.size >= capacity) false else {
			items.addLast(item)
			true
		}
	}

	fun poll(): T? = synchronized(this) { items.removeFirstOrNull() }
}

class Account(var balance: Long)

fun transfer(from: Account, to: Account, amount: Long, lock: Any) {
	synchronized(lock) {
		from.balance -= amount
		to.balance += amount
	}
}

class Squarer(private val from: Int, private val until: Int, private val into: IntArray) : Thread() {
	override fun run() {
		for (i in from until until) into[i] = i * i
	}
}

@Test
fun main() {
	// Each worker writes its own slice.
	val squares = IntArray(1000)
	val workers = (0 until 4).map { Squarer(it * 250, (it + 1) * 250, squares) }
	workers.forEach { it.start() }
	workers.forEach { it.join() }
	println(squares.sum().toLong() + squares.last())

	// A counter under a lock and an atomic, from 8 threads.
	val lock = Any()
	var locked = 0
	val atomic = AtomicInt(0)
	val atomicLong = AtomicLong(0)
	val threads = (1..8).map { n ->
		thread {
			repeat(1000) {
				synchronized(lock) { locked++ }
				atomic.incrementAndFetch()
				atomicLong.addAndFetch(n.toLong())
			}
		}
	}
	threads.forEach { it.join() }
	println("locked $locked atomic ${atomic.load()} long ${atomicLong.load()}")

	// compareAndSet as a spin to take the largest value.
	val maximum = AtomicInt(Int.MIN_VALUE)
	(0 until 6).map { n ->
		thread {
			for (v in listOf(n * 7 % 11, n * 13 % 17, 3)) {
				while (true) {
					val current = maximum.load()
					if (v <= current || maximum.compareAndSet(current, v)) break
				}
			}
		}
	}.forEach { it.join() }
	println("maximum ${maximum.load()}")

	// `lazy` and an object, first reached from several threads at once.
	val cache = Cache()
	val sizes = IntArray(6)
	(0 until 6).map { i -> thread { sizes[i] = cache.table.size + Registry.names.size } }.forEach { it.join() }
	println("sizes ${sizes.toList()} lazy ran ${cache.initializations.load()} object ran ${Registry.created.load()}")

	// Producers and one consumer.
	val queue = BoundedQueue<Int>(16)
	val consumed = AtomicLong(0)
	val received = AtomicInt(0)
	val producers = (0 until 3).map { p ->
		thread {
			for (i in 1..200) {
				while (!queue.offer(p * 1000 + i)) sleep(0)
			}
		}
	}
	val consumer = thread {
		while (received.load() < 600) {
			val item = queue.poll()
			if (item == null) {
				sleep(0)
				continue
			}
			consumed.addAndFetch(item.toLong())
			received.incrementAndFetch()
		}
	}
	producers.forEach { it.join() }
	consumer.join()
	println("consumed ${received.load()} items, total ${consumed.load()}")

	// Transfers between accounts keep the total.
	val accounts = List(4) { Account(1000) }
	val bankLock = Any()
	(0 until 4).map { t ->
		thread {
			repeat(500) { i ->
				transfer(accounts[(t + i) % 4], accounts[(t + i + 1) % 4], (i % 7).toLong(), bankLock)
			}
		}
	}.forEach { it.join() }
	println("total ${accounts.sumOf { it.balance }}")

	// Threads that start threads, results gathered under a lock.
	val results = ArrayList<String>()
	val parents = (0 until 3).map { p ->
		thread {
			val children = (0 until 3).map { c -> thread { synchronized(results) { results += "$p.$c" } } }
			children.forEach { it.join() }
			synchronized(results) { results += "$p done" }
		}
	}
	parents.forEach { it.join() }
	println(results.sorted())

	// A thread created but started later, and an atomic reference.
	val message = AtomicReference("none")
	val later = thread(start = false) { message.store("set by the thread") }
	println(message.load())
	later.start()
	later.join()
	println(message.load())
	println(message.exchange("swapped") + " -> " + message.load())
	val flag = AtomicBoolean(false)
	println(flag.compareAndSet(false, true).toString() + " " + flag.compareAndSet(false, true) + " " + flag.load())
	val array = AtomicIntArray(4)
	(0 until 4).map { t -> thread { repeat(100) { array.incrementAndFetchAt(t % 2) } } }.forEach { it.join() }
	println(array)
}
