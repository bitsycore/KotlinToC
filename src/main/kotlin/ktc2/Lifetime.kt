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
 * States that a reference property never closes a cycle (the `next` of a list that is not
 * circular, for example), which silences the reference-cycle warning for it. If a cycle forms
 * anyway its objects are never freed.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Acyclic

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
