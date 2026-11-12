package ktc2

import ktc2.internal.Storage
import ktc2.internal.arenaEnter
import ktc2.internal.arenaLeave
import ktc2.internal.weakCell
import ktc2.internal.weakGet

// ==================
// MARK: Lifetime
// ==================

/**
 * Runs [inBlock] with every object it creates - directly or in the functions it calls -
 * carved out of blocks of [inSize] bytes that are freed together when the block returns.
 * An object still referred to afterwards (the result, typically) stays valid: it keeps its
 * block alive until it is released itself.
 */
public inline fun <R> arena(inSize: Int, inBlock: () -> R): R {
	val vArena = arenaEnter(inSize)
	try {
		return inBlock()
	} finally {
		arenaLeave(vArena)
	}
}

/**
 * An array that stores its elements themselves. The C side of `ktc2.ValueArray`: one block
 * of the element type, so structs and numbers lie in it unboxed.
 */
public class ValueArray<T>(inSize: Int, inInit: (Int) -> T) : Iterable<T> {

	private val mItems = Storage<T>(inSize)

	init {
		for (vIndex in 0 until inSize) mItems[vIndex] = inInit(vIndex)
	}

	public val size: Int get() = mItems.size

	public val indices: IntRange get() = 0 until mItems.size

	public operator fun get(inIndex: Int): T = mItems[inIndex]

	public operator fun set(inIndex: Int, inValue: T) {
		mItems[inIndex] = inValue
	}

	override fun iterator(): Iterator<T> = ValueArrayIterator(this)
}

private class ValueArrayIterator<T>(private val mArray: ValueArray<T>) : Iterator<T> {
	private var mIndex = 0

	override fun hasNext(): Boolean = mIndex < mArray.size

	override fun next(): T {
		if (mIndex >= mArray.size) throw NoSuchElementException()
		return mArray[mIndex++]
	}
}

/**
 * Implemented by classes that own something else than memory. [deinit] runs once, when the
 * last reference to the object goes and before its properties are given up. The C side of
 * `ktc2.Deinit`: the runtime makes the call.
 */
public interface Deinit {
	public fun deinit()
}

/**
 * What [createCleaner] returns: the cleanup action runs when this object ends. The C side of
 * `ktc2.Cleaner`.
 */
public interface Cleaner

/** A [Cleaner] that is kept as an object: it runs its action when the last reference to it goes. */
private class ResourceCleaner<T>(private val mResource: T, private val mAction: (T) -> Unit) : Cleaner, Deinit {
	override fun deinit() {
		mAction(mResource)
	}
}

/**
 * A [Cleaner] that runs [cleanupAction] with [resource] when it ends. The C side of
 * `ktc2.createCleaner`: the action runs at once when the last reference to the cleaner goes.
 * Declared as `private val cleaner = createCleaner(resource) { … }` in a class, with a lambda
 * that uses nothing from around it, no object is made at all: the class runs the action with
 * the resource when an instance ends, and holds the resource in that property unless it is
 * already a `val` of the class.
 */
public fun <T> createCleaner(resource: T, cleanupAction: (resource: T) -> Unit): Cleaner = ResourceCleaner(resource, cleanupAction)

/**
 * Non-owning reference usable as a value. The C side of `ktc2.WeakRef`: it holds the weak
 * cell of its target instead of a `java.lang.ref.WeakReference`.
 */
public class WeakRef<T : Any>(inTarget: T) {

	private val mCell: Any? = weakCell(inTarget)

	/** The target, or `null` once it has been destroyed. */
	@Suppress("UNCHECKED_CAST")
	public fun get(): T? = weakGet(mCell) as T?
}
