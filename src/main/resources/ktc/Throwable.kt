package ktc

/**
 * Root of the KTC exception hierarchy — a real class hierarchy, mirroring Kotlin:
 *
 *     Throwable
 *     ├── Exception
 *     │   └── RuntimeException
 *     │       ├── IllegalStateException
 *     │       ├── IllegalArgumentException ── NumberFormatException
 *     │       ├── IndexOutOfBoundsException
 *     │       ├── NoSuchElementException
 *     │       └── UnsupportedOperationException
 *     └── Error ── NotImplementedError
 *
 * Define exceptions by extending any open class of the hierarchy:
 *
 *     class ParseError(message: String, val pos: Int) : Exception(message)
 *     ...
 *     throw ParseError("unexpected token", 12)
 *
 * Catching follows Kotlin semantics, matched top to bottom — a catch on a
 * supertype catches every subtype (`catch (e: Exception)` catches ParseError;
 * it does NOT catch Error/NotImplementedError, only `catch (e: Throwable)` does):
 *
 *     try {
 *         risky()
 *     } catch (e: ParseError) {        // exact class
 *         println(e.pos)
 *     } catch (e: Exception) {         // any Exception subtype
 *         println(e.message)
 *     } finally {
 *         cleanup()
 *     }
 *
 * `throw` deep-copies the exception object plus its message bytes into a
 * per-thread arena so they survive the longjmp to the catching frame. Fields
 * other than `message` are NOT deep-copied: keep extra fields to value types
 * (Int, Bool, ...) or literal-backed Strings. A caught binding is copied onto
 * the catching frame, so the catch body may rethrow (`throw e`) or throw a
 * new exception freely.
 */
open class Throwable(val message: String = "")

/** Base class for catchable exceptions — extend this (or a subclass) for user errors. */
open class Exception(message: String = "") : Throwable(message)

/** General-purpose runtime exception — the go-to base for ad-hoc throws. */
open class RuntimeException(message: String = "") : Exception(message)

/** Thrown when a function is called while the object is in an illegal state ([error], [check]). */
open class IllegalStateException(message: String = "") : RuntimeException(message)

/** Thrown when a function receives an argument it cannot accept ([require]). */
open class IllegalArgumentException(message: String = "") : RuntimeException(message)

/** Thrown when an index is outside the bounds of a collection or array. */
open class IndexOutOfBoundsException(message: String = "") : RuntimeException(message)

/** Thrown when a requested element does not exist in a collection. */
open class NoSuchElementException(message: String = "") : RuntimeException(message)

/** Thrown when an operation is not supported by the receiver. */
open class UnsupportedOperationException(message: String = "") : RuntimeException(message)

/** Thrown by [String.toInt] / [String.toLong] / ... when the text is not a valid number. */
class NumberFormatException(message: String = "") : IllegalArgumentException(message)

/**
 * Base class for serious problems an application should usually NOT catch —
 * `catch (e: Exception)` does not match Error subtypes, `catch (e: Throwable)` does.
 */
open class Error(message: String = "") : Throwable(message)

/** Thrown by [TODO]. An [Error], mirroring Kotlin. */
class NotImplementedError(message: String = "An operation is not implemented.") : Error(message)
