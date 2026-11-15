@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package kotlin.concurrent

/*
ktc2 platform layer: Kotlin/Native's atomics of `kotlin.concurrent` (`AtomicInt`, `AtomicLong`,
`AtomicReference` with `value`, `compareAndSet`, `addAndGet`), which libraries written for
Kotlin/Native use (atomicfu). Each holds one of `kotlin.concurrent.atomics`, the common ones.
*/

/** An Int that threads change atomically. */
public class AtomicInt(value: Int) {
	private val mCell = kotlin.concurrent.atomics.AtomicInt(value)

	public var value: Int
		get() = mCell.load()
		set(newValue) = mCell.store(newValue)

	public fun getAndSet(newValue: Int): Int = mCell.exchange(newValue)
	public fun compareAndSet(expected: Int, newValue: Int): Boolean = mCell.compareAndSet(expected, newValue)
	public fun compareAndExchange(expected: Int, newValue: Int): Int = mCell.compareAndExchange(expected, newValue)
	public fun getAndAdd(delta: Int): Int = mCell.fetchAndAdd(delta)
	public fun addAndGet(delta: Int): Int = mCell.addAndFetch(delta)
	public fun getAndIncrement(): Int = mCell.fetchAndAdd(1)
	public fun incrementAndGet(): Int = mCell.addAndFetch(1)
	public fun getAndDecrement(): Int = mCell.fetchAndAdd(-1)
	public fun decrementAndGet(): Int = mCell.addAndFetch(-1)
	public fun increment() { mCell.fetchAndAdd(1) }
	public fun decrement() { mCell.fetchAndAdd(-1) }
	override fun toString(): String = value.toString()
}

/** A Long that threads change atomically. */
public class AtomicLong(value: Long = 0L) {
	private val mCell = kotlin.concurrent.atomics.AtomicLong(value)

	public var value: Long
		get() = mCell.load()
		set(newValue) = mCell.store(newValue)

	public fun getAndSet(newValue: Long): Long = mCell.exchange(newValue)
	public fun compareAndSet(expected: Long, newValue: Long): Boolean = mCell.compareAndSet(expected, newValue)
	public fun compareAndExchange(expected: Long, newValue: Long): Long = mCell.compareAndExchange(expected, newValue)
	public fun getAndAdd(delta: Long): Long = mCell.fetchAndAdd(delta)
	public fun addAndGet(delta: Long): Long = mCell.addAndFetch(delta)
	public fun getAndIncrement(): Long = mCell.fetchAndAdd(1L)
	public fun incrementAndGet(): Long = mCell.addAndFetch(1L)
	public fun getAndDecrement(): Long = mCell.fetchAndAdd(-1L)
	public fun decrementAndGet(): Long = mCell.addAndFetch(-1L)
	public fun increment() { mCell.fetchAndAdd(1L) }
	public fun decrement() { mCell.fetchAndAdd(-1L) }
	override fun toString(): String = value.toString()
}

/** A reference that threads change atomically; compared by identity. */
public class AtomicReference<T>(value: T) {
	private val mCell = kotlin.concurrent.atomics.AtomicReference(value)

	public var value: T
		get() = mCell.load()
		set(newValue) = mCell.store(newValue)

	public fun getAndSet(newValue: T): T = mCell.exchange(newValue)
	public fun compareAndSet(expected: T, newValue: T): Boolean = mCell.compareAndSet(expected, newValue)
	public fun compareAndExchange(expected: T, newValue: T): T = mCell.compareAndExchange(expected, newValue)
	override fun toString(): String = "AtomicReference(value=$value)"
}
