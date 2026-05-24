package ktc.std


/**
 * Represents a generic pair of two values.
 *
 * There is no meaning attached to values in this class, it can be used for any purpose.
 * Pair exhibits value semantics, i.e. two pairs are equal if both components are equal.
 *
 * @param A type of the first value.
 * @param B type of the second value.
 * @property first First value.
 * @property second Second value.
 * @constructor Creates a new instance of Pair.
 */
data class Pair<A, B>(
    val first: A,
    val second: B
)

/**
 * Creates a Pair from this and [that] using the infix toStd operator.
 * The `to` BinExpr operator is handled directly by the code generator (Phase 4).
 * A `to` extension function is intentionally left out; the BinExpr handler calls
 * Pair_primaryConstructor directly when the stdlib Pair class is active.
 */
infix fun <A, B> A.toStd(that: B): Pair<A, B> = Pair(this, that)

/**
 * Converts this pair into a list.
 */
fun <T> Pair<T, T>.toList(): List<T> = listOf(first, second)

/**
 * Represents a triad of values
 *
 * There is no meaning attached to values in this class, it can be used for any purpose.
 * Triple exhibits value semantics, i.e. two triples are equal if both components are equal.
 * An example of decomposing it into values:
 *
 * @param A type of the first value.
 * @param B type of the second value.
 * @param C type of the third value.
 * @property first First value.
 * @property second Second value.
 * @property third Third value.
 */
data class Triple<A, B, C>(
    val first: A,
    val second: B,
    val third: C
)

/**
 * Converts this triple into a list.
 */
fun <T> Triple<T, T, T>.toList(): List<T> = listOf(first, second, third)
