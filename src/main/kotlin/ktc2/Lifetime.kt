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
 * What [createCleaner] returns. It has no members: it is kept, usually in a private property
 * of the object that owns the resource, so that it ends when that object does.
 */
sealed interface Cleaner

private class RegisteredCleaner : Cleaner

/** The JVM's cleaner, which runs the actions on a thread of its own once the collector finds a [Cleaner] unreachable. */
private val kJvmCleaner: java.lang.ref.Cleaner by lazy { java.lang.ref.Cleaner.create() }

/**
 * A [Cleaner] that runs [cleanupAction] with [resource] after it has become unreachable: the
 * way to release something that is not memory which works everywhere. It has the signature and
 * the rules of Kotlin/Native's `kotlin.native.ref.createCleaner`:
 *
 * ```
 * class Wrapper {
 *     private val resource = Resource()
 *     private val cleaner = createCleaner(resource) { it.dispose() }
 * }
 * ```
 *
 * - [cleanupAction] must not use anything from around it (not `this`, not a local): only the
 *   resource it is handed. A resource or an action that refers to the object holding the
 *   cleaner keeps both alive for ever, and the action never runs;
 * - when it runs differs: in C at once, when the last reference to the cleaner goes, on the
 *   thread that gave it up; on the JVM some time after, on another thread, when the collector
 *   gets to it, and possibly never;
 * - a cleaner that is still alive when the program ends does not run.
 *
 * In C a cleaner declared as above costs nothing, not even a field: the class runs the action
 * with its `resource` property when an instance ends. (With a resource that is not a `val` of
 * the class, the property of the cleaner holds it.)
 */
fun <T> createCleaner(resource: T, cleanupAction: (resource: T) -> Unit): Cleaner {
	val vCleaner = RegisteredCleaner()
	kJvmCleaner.register(vCleaner) { cleanupAction(resource) }
	return vCleaner
}
