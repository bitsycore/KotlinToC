@file:Suppress("NOTHING_TO_INLINE")

package kotlin.internal.dtoa

/*
The array accesses Kotlin/Native's number conversion (nativeWasmMain/kotlin/internal/dtoa and
NumberConverter.kt, taken as they are) declares `expect`. Kotlin/Native reads without a bounds
check; the conversion never leaves its arrays, so the check ktc2 keeps changes nothing.
*/

internal actual inline fun longArrayGetUnsafe(array: LongArray, index: Int): Long = array[index]

internal actual inline fun longArraySetUnsafe(array: LongArray, index: Int, value: Long) {
	array[index] = value
}

internal actual inline fun copyIntoUnsafe(source: LongArray, destination: LongArray, destinationOffset: Int, startIndex: Int, endIndex: Int) {
	source.copyInto(destination, destinationOffset, startIndex, endIndex)
}

internal actual inline fun doubleArrayGetUnsafe(array: DoubleArray, index: Int): Double = array[index]

internal actual inline fun doubleArraySetUnsafe(array: DoubleArray, index: Int, value: Double) {
	array[index] = value
}

internal actual inline fun intArrayGetUnsafe(array: IntArray, index: Int): Int = array[index]

internal actual inline fun intArraySetUnsafe(array: IntArray, index: Int, value: Int) {
	array[index] = value
}
