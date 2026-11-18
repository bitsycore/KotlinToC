@file:Suppress("NOTHING_TO_INLINE", "RedundantVisibilityModifier", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "UNCHECKED_CAST")

package kotlinx.atomicfu

import kotlin.internal.InlineOnly
import kotlin.reflect.KProperty
import kotlinx.atomicfu.TraceBase.None
import ktc2.internal.atomicIntCell
import ktc2.internal.atomicLongCell
import ktc2.internal.atomicRefCompareAndSet
import ktc2.internal.atomicRefExchange
import ktc2.internal.atomicRefLoad
import ktc2.internal.atomicRefStore

/*
ktc2's atomicfu atomics, in the place of its nativeMain's AtomicFU.kt (this file has another name: a file system that ignores case would take the two for one). Each holds its value itself, as the
fields the atomicfu compiler plugin makes of them do: a number is changed with the atomic operations of the C
compiler on that one field (`atomicIntCell`), a reference by the runtime under the monitor of the atomic
(`ktc2_atomicRefLoad`...: its count is kept with it), which holds the object the atomic is in without a count. A property of one of these types is kept inside the
object that owns it (ktc2's importer makes it as `@Value`), so an atomic is no object of its own: an object
that refers to itself through its atomic (`_next = atomic(this)`) refers to itself.
*/

@PublishedApi
internal const val kLoad = 0
@PublishedApi
internal const val kStore = 1
@PublishedApi
internal const val kExchange = 2
@PublishedApi
internal const val kCompareAndExchange = 3
@PublishedApi
internal const val kFetchAndAdd = 4

public actual fun <T> atomic(initial: T, trace: TraceBase): AtomicRef<T> = AtomicRef<T>(initial)
public actual fun <T> atomic(initial: T): AtomicRef<T> = atomic(initial, None)
public actual fun atomic(initial: Int, trace: TraceBase): AtomicInt = AtomicInt(initial)
public actual fun atomic(initial: Int): AtomicInt = atomic(initial, None)
public actual fun atomic(initial: Long, trace: TraceBase): AtomicLong = AtomicLong(initial)
public actual fun atomic(initial: Long): AtomicLong = atomic(initial, None)
public actual fun atomic(initial: Boolean, trace: TraceBase): AtomicBoolean = AtomicBoolean(if (initial) 1 else 0)
public actual fun atomic(initial: Boolean): AtomicBoolean = atomic(initial, None)

// ==================
// MARK: AtomicRef
// ==================

public actual class AtomicRef<T> internal constructor(initial: T) {
	/** What it holds, and the object it is held in place in (0 if none): the runtime's (`ktc2_AtomicRefCell`), which counts. */
	@PublishedApi
	internal var held: Long = 0L

	@PublishedApi
	internal var owner: Long = 0L

	init {
		atomicRefStore(this, initial)
	}

	public actual inline var value: T
		get() = atomicRefLoad(this) as T
		set(value) {
			atomicRefStore(this, value)
		}

	@InlineOnly
	public actual inline operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value

	@InlineOnly
	public actual inline operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
		this.value = value
	}

	public actual inline fun lazySet(value: T) {
		atomicRefStore(this, value)
	}

	/** Compares by identity, as atomicfu does on every platform. */
	public actual inline fun compareAndSet(expect: T, update: T): Boolean = atomicRefCompareAndSet(this, expect, update)

	public actual fun getAndSet(value: T): T = atomicRefExchange(this, value) as T

	override fun toString(): String = value.toString()
}

// ==================
// MARK: AtomicBoolean
// ==================

public actual class AtomicBoolean internal constructor(@PublishedApi internal var cell: Int) {
	public actual inline var value: Boolean
		get() = atomicIntCell(this, kLoad, 0, 0) != 0
		set(value) {
			atomicIntCell(this, kStore, if (value) 1 else 0, 0)
		}

	@InlineOnly
	public actual inline operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = value

	@InlineOnly
	public actual inline operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
		this.value = value
	}

	public actual inline fun lazySet(value: Boolean) {
		this.value = value
	}

	public actual fun compareAndSet(expect: Boolean, update: Boolean): Boolean {
		val vExpect = if (expect) 1 else 0
		return atomicIntCell(this, kCompareAndExchange, vExpect, if (update) 1 else 0) == vExpect
	}

	public actual fun getAndSet(value: Boolean): Boolean = atomicIntCell(this, kExchange, if (value) 1 else 0, 0) != 0

	override fun toString(): String = value.toString()
}

// ==================
// MARK: AtomicInt
// ==================

public actual class AtomicInt internal constructor(@PublishedApi internal var cell: Int) {
	public actual inline var value: Int
		get() = atomicIntCell(this, kLoad, 0, 0)
		set(value) {
			atomicIntCell(this, kStore, value, 0)
		}

	@InlineOnly
	public actual inline operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = value

	@InlineOnly
	public actual inline operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
		this.value = value
	}

	public actual inline fun lazySet(value: Int) {
		this.value = value
	}

	public actual inline fun compareAndSet(expect: Int, update: Int): Boolean = atomicIntCell(this, kCompareAndExchange, expect, update) == expect

	public actual fun getAndSet(value: Int): Int = atomicIntCell(this, kExchange, value, 0)

	public actual inline fun getAndIncrement(): Int = atomicIntCell(this, kFetchAndAdd, 1, 0)
	public actual inline fun getAndDecrement(): Int = atomicIntCell(this, kFetchAndAdd, -1, 0)
	public actual inline fun getAndAdd(delta: Int): Int = atomicIntCell(this, kFetchAndAdd, delta, 0)
	public actual inline fun addAndGet(delta: Int): Int = atomicIntCell(this, kFetchAndAdd, delta, 0) + delta
	public actual inline fun incrementAndGet(): Int = atomicIntCell(this, kFetchAndAdd, 1, 0) + 1
	public actual inline fun decrementAndGet(): Int = atomicIntCell(this, kFetchAndAdd, -1, 0) - 1

	public actual inline operator fun plusAssign(delta: Int) {
		getAndAdd(delta)
	}

	public actual inline operator fun minusAssign(delta: Int) {
		getAndAdd(-delta)
	}

	override fun toString(): String = value.toString()
}

// ==================
// MARK: AtomicLong
// ==================

public actual class AtomicLong internal constructor(@PublishedApi internal var cell: Long) {
	public actual inline var value: Long
		get() = atomicLongCell(this, kLoad, 0L, 0L)
		set(value) {
			atomicLongCell(this, kStore, value, 0L)
		}

	@InlineOnly
	public actual inline operator fun getValue(thisRef: Any?, property: KProperty<*>): Long = value

	@InlineOnly
	public actual inline operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
		this.value = value
	}

	public actual inline fun lazySet(value: Long) {
		this.value = value
	}

	public actual inline fun compareAndSet(expect: Long, update: Long): Boolean = atomicLongCell(this, kCompareAndExchange, expect, update) == expect

	public actual fun getAndSet(value: Long): Long = atomicLongCell(this, kExchange, value, 0L)

	public actual inline fun getAndIncrement(): Long = atomicLongCell(this, kFetchAndAdd, 1L, 0L)
	public actual inline fun getAndDecrement(): Long = atomicLongCell(this, kFetchAndAdd, -1L, 0L)
	public actual inline fun getAndAdd(delta: Long): Long = atomicLongCell(this, kFetchAndAdd, delta, 0L)
	public actual inline fun addAndGet(delta: Long): Long = atomicLongCell(this, kFetchAndAdd, delta, 0L) + delta
	public actual inline fun incrementAndGet(): Long = atomicLongCell(this, kFetchAndAdd, 1L, 0L) + 1L
	public actual inline fun decrementAndGet(): Long = atomicLongCell(this, kFetchAndAdd, -1L, 0L) - 1L

	public actual inline operator fun plusAssign(delta: Long) {
		getAndAdd(delta)
	}

	public actual inline operator fun minusAssign(delta: Long) {
		getAndAdd(-delta)
	}

	override fun toString(): String = value.toString()
}
