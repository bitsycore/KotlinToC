@file:Suppress("ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.native.concurrent

/*
ktc2 platform layer: the annotations Kotlin/Native gives a meaning to and common code may use. Common
code compiled for a platform needs them to be there; they change nothing here, as on the JVM, where they
are not there at all: a global stays one for every thread.
*/

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public actual annotation class ThreadLocal

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public actual annotation class SharedImmutable
