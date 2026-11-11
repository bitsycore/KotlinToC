package kotlin.collections

/** Source replacement for the library `map`, to test whether module sources shadow the stdlib jar. */
inline fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R> {
	val vOut = ArrayList<R>()
	for (vItem in this) vOut.add(transform(vItem))
	return vOut
}
