@file:Suppress("ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.concurrent

/*
ktc2 platform layer: `kotlin.concurrent.Volatile` as Kotlin/Native has it, an annotation class (on
the JVM it is an alias of `kotlin.jvm.Volatile`, which an `actual typealias` of common code cannot
name). A field of an object is read and written whole here; what `@Volatile` adds on the JVM, that
other threads see a write, the monitors and the atomics give.
*/

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
public actual annotation class Volatile
