@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package kotlin.collections

/*
ktc2 library code compiled as part of the common source set, so that the standard library's
own common files can call it. It stands in for the few functions of the (very large)
generated _Arrays.kt that the collections code needs.
*/

/** Sorts the array in place by [comparator]: a stable insertion sort. */
public fun <T> Array<out T>.sortWith(comparator: Comparator<in T>) {
	val vArray = this as Array<T>
	for (vIndex in 1 until vArray.size) {
		val vValue = vArray[vIndex]
		var vHole = vIndex
		while (vHole > 0 && comparator.compare(vArray[vHole - 1], vValue) > 0) {
			vArray[vHole] = vArray[vHole - 1]
			vHole--
		}
		vArray[vHole] = vValue
	}
}
