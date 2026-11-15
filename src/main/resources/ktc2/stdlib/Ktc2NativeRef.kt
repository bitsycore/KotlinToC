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
