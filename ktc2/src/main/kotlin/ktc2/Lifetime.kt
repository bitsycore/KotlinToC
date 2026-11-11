package ktc2

import java.lang.ref.WeakReference

// ==================
// MARK: Lifetime
// ==================

/**
 * Marks a nullable reference property as non-owning: it does not keep its target alive and
 * reads as `null` once the target is destroyed. Used to break reference cycles.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Weak

/**
 * Non-owning reference usable as a value (in collections, locals, parameters).
 */
class WeakRef<T : Any>(inTarget: T) {

	private val mRef = WeakReference(inTarget)

	/** The target, or `null` once it has been destroyed. */
	fun get(): T? = mRef.get()
}

/**
 * Implemented by classes that own a non-memory resource. [deinit] runs when the last
 * reference is dropped. On the JVM it is never called automatically; prefer
 * `AutoCloseable` + `use { }` in portable code.
 */
interface Deinit {
	fun deinit()
}
