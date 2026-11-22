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

	// A boxed number, character or boolean is a value: kept as long as the reference is (it holds nothing, so it closes no
	// cycle), where ktc2 would free the box at once and Native's collector keeps it until it runs.
	private var mValue: T? = referred.takeIf { it is Number || it is Char || it is Boolean }

	/** The object, or null once it has ended or [clear] was called. */
	public fun get(): T? = mValue ?: mRef?.get()

	public val value: T? get() = get()

	public fun clear() {
		mRef = null
		mValue = null
	}
}
