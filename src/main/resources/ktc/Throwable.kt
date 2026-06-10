package ktc

/**
 * Root of the KTC exception hierarchy.
 *
 * KTC has no class inheritance, so Throwable/Exception are interfaces and every
 * concrete exception is a value class implementing one of them:
 *
 *     class ParseError(override val message: String, val pos: Int) : Exception
 *     ...
 *     throw ParseError("unexpected token", 12)
 *
 * `message` must be a stored property (a constructor `val`) — `throw` copies the
 * exception object plus its message bytes into a per-thread arena so they survive
 * the longjmp back to the catching frame. Other String/Array/Ref fields are NOT
 * deep-copied: keep extra fields to value types (Int, Bool, ...) or literals.
 *
 * Catching follows Kotlin semantics, matched top to bottom:
 *
 *     try {
 *         risky()
 *     } catch (e: ParseError) {        // concrete class — exact match
 *         println(e.pos)
 *     } catch (e: Exception) {         // interface — matches any implementor
 *         println(e.message)
 *     } finally {
 *         cleanup()
 *     }
 *
 * A caught binding is copied out of the arena onto the catching frame, so the
 * catch body may rethrow it (`throw e`) or throw a new exception freely.
 */
interface Throwable {
    val message: String
}

/**
 * Base interface for catchable exceptions. Implement this for user-defined
 * exception classes; catch `Exception` to handle any of them.
 */
interface Exception : Throwable

/** General-purpose runtime exception — the go-to type for ad-hoc throws. */
class RuntimeException(override val message: String = "") : Exception

/** Thrown when a function is called while the object is in an illegal state. */
class IllegalStateException(override val message: String = "") : Exception

/** Thrown when a function receives an argument it cannot accept. */
class IllegalArgumentException(override val message: String = "") : Exception

/** Thrown when an index is outside the bounds of a collection or array. */
class IndexOutOfBoundsException(override val message: String = "") : Exception

/** Thrown when a requested element does not exist in a collection. */
class NoSuchElementException(override val message: String = "") : Exception

/** Thrown when an operation is not supported by the receiver. */
class UnsupportedOperationException(override val message: String = "") : Exception

/** Thrown by [String.toInt] / [String.toLong] / ... when the text is not a valid number. */
class NumberFormatException(override val message: String = "") : Exception

/**
 * Thrown by [TODO]. Implements Throwable directly (not Exception), mirroring
 * Kotlin where NotImplementedError is an Error: `catch (e: Exception)` does
 * not swallow it, only `catch (e: Throwable)` does.
 */
class NotImplementedError(override val message: String = "An operation is not implemented.") : Throwable
