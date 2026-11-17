package kotlin.native.concurrent

/*
ktc2 platform layer: what Kotlin/Native still declares of its former memory manager, for the
libraries that name it. Nothing is frozen: objects are shared between threads as they are.
*/

/** [this], unchanged. */
public fun <T> T.freeze(): T = this

/** Always false. */
public val Any?.isFrozen: Boolean get() = false
