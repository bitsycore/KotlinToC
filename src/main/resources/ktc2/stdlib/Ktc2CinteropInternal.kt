@file:Suppress("UNUSED_PARAMETER")

package kotlinx.cinterop.internal

// ==================
// MARK: What interop is built from
// ==================

/*
The interop library and the bindings generated from `.def` files are ordinary Kotlin written
with the three functions below. The compiler replaces each call by C text, so a binding costs
exactly what the C it names costs.
*/

/** The C type a class of bindings stands for: `struct point`, `int32_t`. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class CType(val spelling: String)

/** The headers the C text in this file needs, each as written after `#include` (`<stdio.h>`, `"my.h"`). */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
public annotation class CHeaders(vararg val names: String)

/** Marks a binding of a C function taking `...`: each extra argument of a call is passed as it is. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class CVariadic(val name: String)

/**
 * The C expression [text], in which `$0`, `$1`… stand for [arguments]. A String argument
 * stands for its `const char*`. [text] has to be a literal.
 */
public fun <R> cExpr(text: String, vararg arguments: Any?): R = throw NotImplementedError()

/** Calls the C function [function] points at with [arguments]; the C types are those of the arguments and of [R]. */
public fun <R> cCall(function: Any?, vararg arguments: Any?): R = throw NotImplementedError()

/** `sizeof` of the C type behind [T]. */
public fun <T> cSizeOf(): Long = throw NotImplementedError()

/** `_Alignof` of the C type behind [T]. */
public fun <T> cAlignOf(): Int = throw NotImplementedError()

/** The address of the C function generated for the top-level function [reference] names. */
public fun <R> cFunction(reference: Any): R = throw NotImplementedError()
