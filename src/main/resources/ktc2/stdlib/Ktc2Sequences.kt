@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package kotlin.sequences

/*
ktc2 platform layer for kotlin.sequences.
*/

/** A sequence that can be iterated only once. */
internal actual class ConstrainedOnceSequence<T> actual constructor(sequence: Sequence<T>) : Sequence<T> {
	private var source: Sequence<T>? = sequence

	actual override fun iterator(): Iterator<T> {
		val vSequence = source ?: throw IllegalStateException("This sequence can be consumed only once.")
		source = null
		return vSequence.iterator()
	}
}
