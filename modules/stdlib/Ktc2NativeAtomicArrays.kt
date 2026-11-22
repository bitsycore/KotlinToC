@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package kotlin.concurrent

/*
ktc2 platform layer: Kotlin/Native's atomic arrays of `kotlin.concurrent` (kotlin-native/runtime/src/main/kotlin/kotlin/
concurrent/AtomicArrays.kt), the ones before `kotlin.concurrent.atomics`: `length`, `get`/`set`, `addAndGet`... Each
holds one of `kotlin.concurrent.atomics`, as `AtomicInt` of this package does, and checks its index with Native's
message.
*/

// ==================
// MARK: AtomicIntArray
// ==================

/** An IntArray whose elements threads change atomically. */
@ExperimentalStdlibApi
public class AtomicIntArray {
	private val mCells: kotlin.concurrent.atomics.AtomicIntArray

	public constructor(size: Int) {
		mCells = kotlin.concurrent.atomics.AtomicIntArray(size)
	}

	@PublishedApi
	internal constructor(array: IntArray) {
		mCells = kotlin.concurrent.atomics.AtomicIntArray(array)
	}

	public val length: Int get() = mCells.size

	public operator fun get(index: Int): Int = mCells.loadAt(checkBounds(index))
	public operator fun set(index: Int, newValue: Int): Unit = mCells.storeAt(checkBounds(index), newValue)
	public fun getAndSet(index: Int, newValue: Int): Int = mCells.exchangeAt(checkBounds(index), newValue)
	public fun compareAndSet(index: Int, expectedValue: Int, newValue: Int): Boolean = mCells.compareAndSetAt(checkBounds(index), expectedValue, newValue)
	public fun compareAndExchange(index: Int, expectedValue: Int, newValue: Int): Int = mCells.compareAndExchangeAt(checkBounds(index), expectedValue, newValue)
	public fun getAndAdd(index: Int, delta: Int): Int = mCells.fetchAndAddAt(checkBounds(index), delta)
	public fun addAndGet(index: Int, delta: Int): Int = mCells.addAndFetchAt(checkBounds(index), delta)
	public fun getAndIncrement(index: Int): Int = mCells.fetchAndAddAt(checkBounds(index), 1)
	public fun incrementAndGet(index: Int): Int = mCells.addAndFetchAt(checkBounds(index), 1)
	public fun getAndDecrement(index: Int): Int = mCells.fetchAndAddAt(checkBounds(index), -1)
	public fun decrementAndGet(index: Int): Int = mCells.addAndFetchAt(checkBounds(index), -1)

	public override fun toString(): String = mCells.toString()

	private fun checkBounds(index: Int): Int {
		if (index < 0 || index >= mCells.size) throw IndexOutOfBoundsException("The index $index is out of the bounds of the AtomicIntArray with size ${mCells.size}.")
		return index
	}
}

@ExperimentalStdlibApi
public inline fun AtomicIntArray(size: Int, init: (Int) -> Int): AtomicIntArray {
	val vInner = IntArray(size)
	for (vIndex in 0 until size) vInner[vIndex] = init(vIndex)
	return AtomicIntArray(vInner)
}

// ==================
// MARK: AtomicLongArray
// ==================

/** A LongArray whose elements threads change atomically. */
@ExperimentalStdlibApi
public class AtomicLongArray {
	private val mCells: kotlin.concurrent.atomics.AtomicLongArray

	public constructor(size: Int) {
		mCells = kotlin.concurrent.atomics.AtomicLongArray(size)
	}

	@PublishedApi
	internal constructor(array: LongArray) {
		mCells = kotlin.concurrent.atomics.AtomicLongArray(array)
	}

	public val length: Int get() = mCells.size

	public operator fun get(index: Int): Long = mCells.loadAt(checkBounds(index))
	public operator fun set(index: Int, newValue: Long): Unit = mCells.storeAt(checkBounds(index), newValue)
	public fun getAndSet(index: Int, newValue: Long): Long = mCells.exchangeAt(checkBounds(index), newValue)
	public fun compareAndSet(index: Int, expectedValue: Long, newValue: Long): Boolean = mCells.compareAndSetAt(checkBounds(index), expectedValue, newValue)
	public fun compareAndExchange(index: Int, expectedValue: Long, newValue: Long): Long = mCells.compareAndExchangeAt(checkBounds(index), expectedValue, newValue)
	public fun getAndAdd(index: Int, delta: Long): Long = mCells.fetchAndAddAt(checkBounds(index), delta)
	public fun addAndGet(index: Int, delta: Long): Long = mCells.addAndFetchAt(checkBounds(index), delta)
	public fun getAndIncrement(index: Int): Long = mCells.fetchAndAddAt(checkBounds(index), 1L)
	public fun incrementAndGet(index: Int): Long = mCells.addAndFetchAt(checkBounds(index), 1L)
	public fun getAndDecrement(index: Int): Long = mCells.fetchAndAddAt(checkBounds(index), -1L)
	public fun decrementAndGet(index: Int): Long = mCells.addAndFetchAt(checkBounds(index), -1L)

	public override fun toString(): String = mCells.toString()

	private fun checkBounds(index: Int): Int {
		if (index < 0 || index >= mCells.size) throw IndexOutOfBoundsException("The index $index is out of the bounds of the AtomicLongArray with size ${mCells.size}.")
		return index
	}
}

@ExperimentalStdlibApi
public inline fun AtomicLongArray(size: Int, init: (Int) -> Long): AtomicLongArray {
	val vInner = LongArray(size)
	for (vIndex in 0 until size) vInner[vIndex] = init(vIndex)
	return AtomicLongArray(vInner)
}

// ==================
// MARK: AtomicArray
// ==================

/** An Array whose elements threads change atomically; compared by identity. */
@ExperimentalStdlibApi
public class AtomicArray<T> {
	private val mCells: kotlin.concurrent.atomics.AtomicArray<T>

	@PublishedApi
	internal constructor(array: Array<T>) {
		mCells = kotlin.concurrent.atomics.AtomicArray(array)
	}

	public val length: Int get() = mCells.size

	public operator fun get(index: Int): T = mCells.loadAt(checkBounds(index))
	public operator fun set(index: Int, newValue: T): Unit = mCells.storeAt(checkBounds(index), newValue)
	public fun getAndSet(index: Int, newValue: T): T = mCells.exchangeAt(checkBounds(index), newValue)
	public fun compareAndSet(index: Int, expectedValue: T, newValue: T): Boolean = mCells.compareAndSetAt(checkBounds(index), expectedValue, newValue)
	public fun compareAndExchange(index: Int, expectedValue: T, newValue: T): T = mCells.compareAndExchangeAt(checkBounds(index), expectedValue, newValue)

	public override fun toString(): String = mCells.toString()

	private fun checkBounds(index: Int): Int {
		if (index < 0 || index >= mCells.size) throw IndexOutOfBoundsException("The index $index is out of the bounds of the AtomicArray with size ${mCells.size}.")
		return index
	}
}

@ExperimentalStdlibApi
@Suppress("UNCHECKED_CAST")
public inline fun <reified T> AtomicArray(size: Int, init: (Int) -> T): AtomicArray<T> {
	val vInner = arrayOfNulls<T>(size)
	for (vIndex in 0 until size) vInner[vIndex] = init(vIndex)
	return AtomicArray(vInner as Array<T>)
}
