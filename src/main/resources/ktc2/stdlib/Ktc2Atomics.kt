@file:Suppress("ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "NOTHING_TO_INLINE", "UNCHECKED_CAST")
@file:OptIn(ExperimentalAtomicApi::class, kotlin.contracts.ExperimentalContracts::class)

package kotlin.concurrent.atomics

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import ktc2.internal.atomicInt
import ktc2.internal.atomicLong
import ktc2.internal.monitorEnter
import ktc2.internal.monitorExit
import ktc2.internal.throwIndexOutOfBounds

/*
ktc2 platform layer: `kotlin.concurrent.atomics`. A number lives in one element of an IntArray or a
LongArray, which the runtime changes with the atomic operations of the C compiler (`ktc2_atomicInt`). A
reference, which has a count to keep, is changed under the monitor of its holder; without threads the
monitor costs nothing.
*/

private const val kLoad = 0
private const val kStore = 1
private const val kExchange = 2
private const val kCompareAndExchange = 3
private const val kFetchAndAdd = 4

private fun checkIndex(index: Int, size: Int) {
	if (index < 0 || index >= size) throwIndexOutOfBounds(index, size)
}

// ==================
// MARK: AtomicInt, AtomicLong, AtomicBoolean
// ==================

public actual class AtomicInt public actual constructor(value: Int) {
	private val cell = IntArray(1).also { it[0] = value }

	public actual fun load(): Int = atomicInt(cell, 0, kLoad, 0, 0)
	public actual fun store(newValue: Int) { atomicInt(cell, 0, kStore, newValue, 0) }
	public actual fun exchange(newValue: Int): Int = atomicInt(cell, 0, kExchange, newValue, 0)
	public actual fun compareAndSet(expectedValue: Int, newValue: Int): Boolean = atomicInt(cell, 0, kCompareAndExchange, expectedValue, newValue) == expectedValue
	public actual fun compareAndExchange(expectedValue: Int, newValue: Int): Int = atomicInt(cell, 0, kCompareAndExchange, expectedValue, newValue)
	public actual fun fetchAndAdd(delta: Int): Int = atomicInt(cell, 0, kFetchAndAdd, delta, 0)
	public actual fun addAndFetch(delta: Int): Int = atomicInt(cell, 0, kFetchAndAdd, delta, 0) + delta
	public actual override fun toString(): String = load().toString()
}

public actual inline fun AtomicInt.update(transform: (Int) -> Int): Unit {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		if (compareAndSet(vOld, transform(vOld))) return
	}
}

public actual inline fun AtomicInt.fetchAndUpdate(transform: (Int) -> Int): Int {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		if (compareAndSet(vOld, transform(vOld))) return vOld
	}
}

public actual inline fun AtomicInt.updateAndFetch(transform: (Int) -> Int): Int {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		val vNew = transform(vOld)
		if (compareAndSet(vOld, vNew)) return vNew
	}
}

public actual class AtomicLong public actual constructor(value: Long) {
	private val cell = LongArray(1).also { it[0] = value }

	public actual fun load(): Long = atomicLong(cell, 0, kLoad, 0L, 0L)
	public actual fun store(newValue: Long) { atomicLong(cell, 0, kStore, newValue, 0L) }
	public actual fun exchange(newValue: Long): Long = atomicLong(cell, 0, kExchange, newValue, 0L)
	public actual fun compareAndSet(expectedValue: Long, newValue: Long): Boolean = atomicLong(cell, 0, kCompareAndExchange, expectedValue, newValue) == expectedValue
	public actual fun compareAndExchange(expectedValue: Long, newValue: Long): Long = atomicLong(cell, 0, kCompareAndExchange, expectedValue, newValue)
	public actual fun fetchAndAdd(delta: Long): Long = atomicLong(cell, 0, kFetchAndAdd, delta, 0L)
	public actual fun addAndFetch(delta: Long): Long = atomicLong(cell, 0, kFetchAndAdd, delta, 0L) + delta
	public actual override fun toString(): String = load().toString()
}

public actual inline fun AtomicLong.update(transform: (Long) -> Long): Unit {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		if (compareAndSet(vOld, transform(vOld))) return
	}
}

public actual inline fun AtomicLong.fetchAndUpdate(transform: (Long) -> Long): Long {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		if (compareAndSet(vOld, transform(vOld))) return vOld
	}
}

public actual inline fun AtomicLong.updateAndFetch(transform: (Long) -> Long): Long {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		val vNew = transform(vOld)
		if (compareAndSet(vOld, vNew)) return vNew
	}
}

public actual class AtomicBoolean public actual constructor(value: Boolean) {
	private val cell = IntArray(1).also { it[0] = if (value) 1 else 0 }

	public actual fun load(): Boolean = atomicInt(cell, 0, kLoad, 0, 0) != 0
	public actual fun store(newValue: Boolean) { atomicInt(cell, 0, kStore, if (newValue) 1 else 0, 0) }
	public actual fun exchange(newValue: Boolean): Boolean = atomicInt(cell, 0, kExchange, if (newValue) 1 else 0, 0) != 0

	public actual fun compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean {
		val vExpected = if (expectedValue) 1 else 0
		return atomicInt(cell, 0, kCompareAndExchange, vExpected, if (newValue) 1 else 0) == vExpected
	}

	public actual fun compareAndExchange(expectedValue: Boolean, newValue: Boolean): Boolean =
		atomicInt(cell, 0, kCompareAndExchange, if (expectedValue) 1 else 0, if (newValue) 1 else 0) != 0

	public actual override fun toString(): String = load().toString()
}

// ==================
// MARK: AtomicReference
// ==================

public actual class AtomicReference<T> public actual constructor(value: T) {
	private var held: T = value

	public actual fun load(): T {
		monitorEnter(this)
		val vValue = held
		monitorExit(this)
		return vValue
	}

	public actual fun store(newValue: T) {
		monitorEnter(this)
		held = newValue
		monitorExit(this)
	}

	public actual fun exchange(newValue: T): T {
		monitorEnter(this)
		val vOld = held
		held = newValue
		monitorExit(this)
		return vOld
	}

	/** Compares by identity, as the JVM does. */
	public actual fun compareAndSet(expectedValue: T, newValue: T): Boolean {
		monitorEnter(this)
		val vSame = held === expectedValue
		if (vSame) held = newValue
		monitorExit(this)
		return vSame
	}

	public actual fun compareAndExchange(expectedValue: T, newValue: T): T {
		monitorEnter(this)
		val vOld = held
		if (vOld === expectedValue) held = newValue
		monitorExit(this)
		return vOld
	}

	public actual override fun toString(): String = load().toString()
}

public actual inline fun <T> AtomicReference<T>.update(transform: (T) -> T): Unit {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		if (compareAndSet(vOld, transform(vOld))) return
	}
}

public actual inline fun <T> AtomicReference<T>.fetchAndUpdate(transform: (T) -> T): T {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		if (compareAndSet(vOld, transform(vOld))) return vOld
	}
}

public actual inline fun <T> AtomicReference<T>.updateAndFetch(transform: (T) -> T): T {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = load()
		val vNew = transform(vOld)
		if (compareAndSet(vOld, vNew)) return vNew
	}
}

// ==================
// MARK: Arrays
// ==================

public actual class AtomicIntArray public actual constructor(array: IntArray) {
	private val cells: IntArray = array.copyOf()

	public actual constructor(size: Int) : this(IntArray(size))

	public actual val size: Int get() = cells.size

	public actual fun loadAt(index: Int): Int {
		checkIndex(index, cells.size)
		return atomicInt(cells, index, kLoad, 0, 0)
	}

	public actual fun storeAt(index: Int, newValue: Int) {
		checkIndex(index, cells.size)
		atomicInt(cells, index, kStore, newValue, 0)
	}

	public actual fun exchangeAt(index: Int, newValue: Int): Int {
		checkIndex(index, cells.size)
		return atomicInt(cells, index, kExchange, newValue, 0)
	}

	public actual fun compareAndSetAt(index: Int, expectedValue: Int, newValue: Int): Boolean {
		checkIndex(index, cells.size)
		return atomicInt(cells, index, kCompareAndExchange, expectedValue, newValue) == expectedValue
	}

	public actual fun compareAndExchangeAt(index: Int, expectedValue: Int, newValue: Int): Int {
		checkIndex(index, cells.size)
		return atomicInt(cells, index, kCompareAndExchange, expectedValue, newValue)
	}

	public actual fun fetchAndAddAt(index: Int, delta: Int): Int {
		checkIndex(index, cells.size)
		return atomicInt(cells, index, kFetchAndAdd, delta, 0)
	}

	public actual fun addAndFetchAt(index: Int, delta: Int): Int {
		checkIndex(index, cells.size)
		return atomicInt(cells, index, kFetchAndAdd, delta, 0) + delta
	}

	public actual override fun toString(): String {
		val vText = StringBuilder("[")
		for (vIndex in 0 until cells.size) {
			if (vIndex > 0) vText.append(", ")
			vText.append(atomicInt(cells, vIndex, kLoad, 0, 0))
		}
		return vText.append(']').toString()
	}
}

public actual inline fun AtomicIntArray.updateAt(index: Int, transform: (Int) -> Int): Unit {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		if (compareAndSetAt(index, vOld, transform(vOld))) return
	}
}

public actual inline fun AtomicIntArray.updateAndFetchAt(index: Int, transform: (Int) -> Int): Int {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		val vNew = transform(vOld)
		if (compareAndSetAt(index, vOld, vNew)) return vNew
	}
}

public actual inline fun AtomicIntArray.fetchAndUpdateAt(index: Int, transform: (Int) -> Int): Int {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		if (compareAndSetAt(index, vOld, transform(vOld))) return vOld
	}
}

public actual class AtomicLongArray public actual constructor(array: LongArray) {
	private val cells: LongArray = array.copyOf()

	public actual constructor(size: Int) : this(LongArray(size))

	public actual val size: Int get() = cells.size

	public actual fun loadAt(index: Int): Long {
		checkIndex(index, cells.size)
		return atomicLong(cells, index, kLoad, 0L, 0L)
	}

	public actual fun storeAt(index: Int, newValue: Long) {
		checkIndex(index, cells.size)
		atomicLong(cells, index, kStore, newValue, 0L)
	}

	public actual fun exchangeAt(index: Int, newValue: Long): Long {
		checkIndex(index, cells.size)
		return atomicLong(cells, index, kExchange, newValue, 0L)
	}

	public actual fun compareAndSetAt(index: Int, expectedValue: Long, newValue: Long): Boolean {
		checkIndex(index, cells.size)
		return atomicLong(cells, index, kCompareAndExchange, expectedValue, newValue) == expectedValue
	}

	public actual fun compareAndExchangeAt(index: Int, expectedValue: Long, newValue: Long): Long {
		checkIndex(index, cells.size)
		return atomicLong(cells, index, kCompareAndExchange, expectedValue, newValue)
	}

	public actual fun fetchAndAddAt(index: Int, delta: Long): Long {
		checkIndex(index, cells.size)
		return atomicLong(cells, index, kFetchAndAdd, delta, 0L)
	}

	public actual fun addAndFetchAt(index: Int, delta: Long): Long {
		checkIndex(index, cells.size)
		return atomicLong(cells, index, kFetchAndAdd, delta, 0L) + delta
	}

	public actual override fun toString(): String {
		val vText = StringBuilder("[")
		for (vIndex in 0 until cells.size) {
			if (vIndex > 0) vText.append(", ")
			vText.append(atomicLong(cells, vIndex, kLoad, 0L, 0L))
		}
		return vText.append(']').toString()
	}
}

public actual inline fun AtomicLongArray.updateAt(index: Int, transform: (Long) -> Long): Unit {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		if (compareAndSetAt(index, vOld, transform(vOld))) return
	}
}

public actual inline fun AtomicLongArray.updateAndFetchAt(index: Int, transform: (Long) -> Long): Long {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		val vNew = transform(vOld)
		if (compareAndSetAt(index, vOld, vNew)) return vNew
	}
}

public actual inline fun AtomicLongArray.fetchAndUpdateAt(index: Int, transform: (Long) -> Long): Long {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		if (compareAndSetAt(index, vOld, transform(vOld))) return vOld
	}
}

public actual class AtomicArray<T> public actual constructor(array: Array<T>) {
	private val cells: Array<Any?> = Array(array.size) { array[it] }

	public actual val size: Int get() = cells.size

	public actual fun loadAt(index: Int): T {
		checkIndex(index, cells.size)
		monitorEnter(this)
		val vValue = cells[index]
		monitorExit(this)
		return vValue as T
	}

	public actual fun storeAt(index: Int, newValue: T) {
		checkIndex(index, cells.size)
		monitorEnter(this)
		cells[index] = newValue
		monitorExit(this)
	}

	public actual fun exchangeAt(index: Int, newValue: T): T {
		checkIndex(index, cells.size)
		monitorEnter(this)
		val vOld = cells[index]
		cells[index] = newValue
		monitorExit(this)
		return vOld as T
	}

	/** Compares by identity, as the JVM does. */
	public actual fun compareAndSetAt(index: Int, expectedValue: T, newValue: T): Boolean {
		checkIndex(index, cells.size)
		monitorEnter(this)
		val vSame = cells[index] === expectedValue
		if (vSame) cells[index] = newValue
		monitorExit(this)
		return vSame
	}

	public actual fun compareAndExchangeAt(index: Int, expectedValue: T, newValue: T): T {
		checkIndex(index, cells.size)
		monitorEnter(this)
		val vOld = cells[index]
		if (vOld === expectedValue) cells[index] = newValue
		monitorExit(this)
		return vOld as T
	}

	public actual override fun toString(): String {
		val vText = StringBuilder("[")
		for (vIndex in 0 until cells.size) {
			if (vIndex > 0) vText.append(", ")
			vText.append(loadAt(vIndex))
		}
		return vText.append(']').toString()
	}
}

public actual inline fun <T> AtomicArray<T>.updateAt(index: Int, transform: (T) -> T): Unit {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		if (compareAndSetAt(index, vOld, transform(vOld))) return
	}
}

public actual inline fun <T> AtomicArray<T>.updateAndFetchAt(index: Int, transform: (T) -> T): T {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		val vNew = transform(vOld)
		if (compareAndSetAt(index, vOld, vNew)) return vNew
	}
}

public actual inline fun <T> AtomicArray<T>.fetchAndUpdateAt(index: Int, transform: (T) -> T): T {
	contract { callsInPlace(transform, InvocationKind.AT_LEAST_ONCE) }
	while (true) {
		val vOld = loadAt(index)
		if (compareAndSetAt(index, vOld, transform(vOld))) return vOld
	}
}
