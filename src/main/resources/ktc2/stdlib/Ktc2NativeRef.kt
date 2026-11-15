package kotlin.native.ref

/*
ktc2 platform layer: Kotlin/Native's cleaners, which are ktc2's (`ktc2.createCleaner`): the action
runs when the last reference to the cleaner goes.
*/

/** What [createCleaner] returns. */
public typealias Cleaner = ktc2.Cleaner

/** A [Cleaner] that runs [cleanupAction] with [resource] when it ends. */
@kotlin.experimental.ExperimentalNativeApi
public fun <T> createCleaner(resource: T, cleanupAction: (resource: T) -> Unit): Cleaner = ktc2.createCleaner(resource, cleanupAction)

/** A reference that does not keep [referred] alive, as Kotlin/Native's: over ktc2's [ktc2.WeakRef]. */
@kotlin.experimental.ExperimentalNativeApi
public class WeakReference<T : Any>(referred: T) {
	private var mRef: ktc2.WeakRef<T>? = ktc2.WeakRef(referred)

	/** The object, or null once it has ended or [clear] was called. */
	public fun get(): T? = mRef?.get()

	public val value: T? get() = get()

	public fun clear() {
		mRef = null
	}
}
