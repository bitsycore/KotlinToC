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

/* Creates a Pair from this and [that] using the infix `to` operator.
Resolves through the normal inline-extension dispatch path — there's
no special-cased BinExpr handler. */
infix fun <A, B> A.to(that: B): Pair<A, B> = Pair(this, that)

/**
 * Converts this pair into a list, backed by [allocator].
 */
fun <T> Pair<T, T>.toList(allocator: Ref<Allocator>): List<T> = listOf(allocator, first, second)

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
 * Converts this triple into a list, backed by [allocator].
 */
fun <T> Triple<T, T, T>.toList(allocator: Ref<Allocator>): List<T> = listOf(allocator, first, second, third)
