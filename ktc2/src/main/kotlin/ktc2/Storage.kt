package ktc2

// ==================
// MARK: Storage hints
// ==================

/**
 * Use-site storage hint: the object is stored inline in its slot (field, array element or
 * local) instead of behind a counted reference. Ignored on the JVM.
 *
 * Written on the type: `val origin: @Value Vec2`, `Array<@Value Particle>`.
 */
@Target(AnnotationTarget.TYPE)
@Retention(AnnotationRetention.SOURCE)
annotation class Value

/**
 * Declaration-site form of [Value]: every slot of the annotated class is stored inline.
 * The class must only declare `val` properties.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Struct

/**
 * Fixed element count of an array or string slot, stored inline in its owner.
 *
 * @property inN element count, a compile-time constant
 */
@Target(AnnotationTarget.TYPE, AnnotationTarget.PROPERTY, AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.SOURCE)
annotation class Size(val inN: Int)

/**
 * Runs [inBlock] with every object it creates — directly or in the functions it calls —
 * carved out of blocks of [inSize] bytes that are freed together when the block returns.
 * An object still referred to afterwards (the result, typically) stays valid: it keeps its
 * block alive until it is released itself. On the JVM this only runs the block.
 */
@Suppress("UNUSED_PARAMETER")
inline fun <R> arena(inSize: Int, inBlock: () -> R): R = inBlock()
