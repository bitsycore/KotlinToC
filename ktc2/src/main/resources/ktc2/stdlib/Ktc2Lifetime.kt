package ktc2

import ktc2.internal.weakCell
import ktc2.internal.weakGet

// ==================
// MARK: Lifetime
// ==================

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
