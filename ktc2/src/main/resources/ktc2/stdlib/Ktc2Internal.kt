@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package ktc2.internal

/*
ktc2 platform layer: what the Kotlin standard library expects its platform to provide.
The functions here are the few primitives the rest is written on.
*/

/**
 * A new array of [size] elements of type [T], zero-initialized.
 *
 * ktc2 lowers a call to this directly (the body is never used): the element type is the one
 * [T] stands for in the instantiation being compiled, so `newArray<Int>` is an array of ints.
 */
public fun <T> newArray(size: Int): Array<T> = arrayOfNulls<Any?>(size) as Array<T>

/** Iterates an array; `array.iterator()` is lowered to [arrayIterator]. */
public class ArrayIterator<T>(private val array: Array<out T>) : Iterator<T> {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun next(): T {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun <T> arrayIterator(array: Array<out T>): Iterator<T> = ArrayIterator(array)

/**
 * A String holding [length] UTF-16 code units of [chars] starting at [start].
 * Implemented by the C runtime (`ktc2_stringOfChars`), like every `external` function here.
 */
public external fun stringOfChars(chars: CharArray, start: Int, length: Int): String

// ==================
// MARK: Library functions provided here
// ==================

/*
A library function that has no Kotlin body ktc2 can read (it is implemented on the JVM side)
is redirected to the function here named after it, dots replaced by underscores.
*/

/** `Array<out T>.asList()` */
public fun <T> kotlin_collections_asList(array: Array<out T>): List<T> {
	val vList = ArrayList<T>(array.size)
	for (vElement in array) vList.add(vElement)
	return vList
}
