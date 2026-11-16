@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package ktc2

import kotlin.native.ref.WeakReference

/*
The Kotlin/Native side of the lifetime part of `ktc2.`, for building a ktc2 program with
Kotlin/Native (tools/compare.py): the same API over what Kotlin/Native has. The annotations
change nothing there; the C side is ktc2's platform layer, the JVM side src/main/kotlin/ktc2.
*/

/** A property or an inner class whose reference does not own its target. Nothing on Kotlin/Native, which has a collector. */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Weak

/** A property that never closes a cycle. Nothing on Kotlin/Native. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Acyclic

/** Non-owning reference usable as a value, over Kotlin/Native's own. */
class WeakRef<T : Any>(inTarget: T) {

	private val mRef = WeakReference(inTarget)

	/** The target, or `null` once it has been collected. */
	fun get(): T? = mRef.get()
}

/** What [createCleaner] returns: kept so that it ends when the object holding it does. */
sealed interface Cleaner

private class NativeCleaner(val cleaner: kotlin.native.ref.Cleaner) : Cleaner

/** A [Cleaner] that runs [cleanupAction] with [resource] once it is collected: Kotlin/Native's own. */
fun <T> createCleaner(resource: T, cleanupAction: (resource: T) -> Unit): Cleaner =
	NativeCleaner(kotlin.native.ref.createCleaner(resource, cleanupAction))
