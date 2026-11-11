package ktc2

import ktc2.internal.arenaEnter
import ktc2.internal.arenaLeave
import ktc2.internal.weakCell
import ktc2.internal.weakGet

// ==================
// MARK: Lifetime
// ==================

/**
 * Runs [inBlock] with every object it creates — directly or in the functions it calls —
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
 * Non-owning reference usable as a value. The C side of `ktc2.WeakRef`: it holds the weak
 * cell of its target instead of a `java.lang.ref.WeakReference`.
 */
public class WeakRef<T : Any>(inTarget: T) {

	private val mCell: Any? = weakCell(inTarget)

	/** The target, or `null` once it has been destroyed. */
	@Suppress("UNCHECKED_CAST")
	public fun get(): T? = weakGet(mCell) as T?
}
