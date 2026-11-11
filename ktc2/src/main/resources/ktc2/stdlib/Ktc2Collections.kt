@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_WITHOUT_EXPECT", "NOTHING_TO_INLINE", "UNCHECKED_CAST")

package kotlin.collections

import ktc2.internal.newArray

/*
ktc2 platform layer for kotlin.collections: the declarations the common standard library
leaves to its platform (`expect`), and the collection classes the JVM takes from java.util.
*/

// ==================
// MARK: ArrayList
// ==================

/**
 * A resizable list over a typed array: an `ArrayList<Int>` stores plain ints.
 */
public actual class ArrayList<E> actual constructor(initialCapacity: Int) : MutableList<E>, RandomAccess {

	private var array: Array<E> = newArray(if (initialCapacity > 0) initialCapacity else 10)
	private var length: Int = 0

	public actual constructor() : this(10)

	public actual constructor(elements: Collection<E>) : this(elements.size) {
		addAll(elements)
	}

	actual override val size: Int get() = length

	actual override fun isEmpty(): Boolean = length == 0

	private fun checkIndex(index: Int) {
		if (index < 0 || index >= length) throw IndexOutOfBoundsException("Index: $index, Size: $length")
	}

	private fun checkPosition(index: Int) {
		if (index < 0 || index > length) throw IndexOutOfBoundsException("Index: $index, Size: $length")
	}

	public actual fun ensureCapacity(minCapacity: Int) {
		if (minCapacity <= array.size) return
		var vCapacity = array.size * 2
		if (vCapacity < minCapacity) vCapacity = minCapacity
		val vBigger = newArray<E>(vCapacity)
		for (vIndex in 0 until length) vBigger[vIndex] = array[vIndex]
		array = vBigger
	}

	public actual fun trimToSize() {
	}

	actual override operator fun get(index: Int): E {
		checkIndex(index)
		return array[index]
	}

	actual override operator fun set(index: Int, element: E): E {
		checkIndex(index)
		val vOld = array[index]
		array[index] = element
		return vOld
	}

	actual override fun add(element: E): Boolean {
		ensureCapacity(length + 1)
		array[length] = element
		length++
		return true
	}

	actual override fun add(index: Int, element: E) {
		checkPosition(index)
		ensureCapacity(length + 1)
		var vIndex = length
		while (vIndex > index) {
			array[vIndex] = array[vIndex - 1]
			vIndex--
		}
		array[index] = element
		length++
	}

	actual override fun addAll(elements: Collection<E>): Boolean {
		ensureCapacity(length + elements.size)
		for (vElement in elements) add(vElement)
		return !elements.isEmpty()
	}

	actual override fun addAll(index: Int, elements: Collection<E>): Boolean {
		checkPosition(index)
		var vAt = index
		for (vElement in elements) {
			add(vAt, vElement)
			vAt++
		}
		return !elements.isEmpty()
	}

	actual override fun removeAt(index: Int): E {
		checkIndex(index)
		val vOld = array[index]
		for (vIndex in index until length - 1) array[vIndex] = array[vIndex + 1]
		length--
		return vOld
	}

	actual override fun remove(element: E): Boolean {
		val vIndex = indexOf(element)
		if (vIndex < 0) return false
		removeAt(vIndex)
		return true
	}

	/** Keeps the elements for which membership in [elements] equals [keep]. */
	private fun filterInPlace(elements: Collection<E>, keep: Boolean): Boolean {
		var vKept = 0
		for (vIndex in 0 until length) {
			val vElement = array[vIndex]
			if (elements.contains(vElement) == keep) {
				array[vKept] = vElement
				vKept++
			}
		}
		val vChanged = vKept != length
		length = vKept
		return vChanged
	}

	actual override fun removeAll(elements: Collection<E>): Boolean = filterInPlace(elements, false)

	actual override fun retainAll(elements: Collection<E>): Boolean = filterInPlace(elements, true)

	actual override fun clear() {
		length = 0
	}

	actual override fun contains(element: E): Boolean = indexOf(element) >= 0

	actual override fun containsAll(elements: Collection<E>): Boolean {
		for (vElement in elements) {
			if (!contains(vElement)) return false
		}
		return true
	}

	actual override fun indexOf(element: E): Int {
		for (vIndex in 0 until length) {
			if (array[vIndex] == element) return vIndex
		}
		return -1
	}

	actual override fun lastIndexOf(element: E): Int {
		var vIndex = length - 1
		while (vIndex >= 0) {
			if (array[vIndex] == element) return vIndex
			vIndex--
		}
		return -1
	}

	actual override fun iterator(): MutableIterator<E> = ArrayListIterator(this, 0)

	actual override fun listIterator(): MutableListIterator<E> = ArrayListIterator(this, 0)

	actual override fun listIterator(index: Int): MutableListIterator<E> {
		checkPosition(index)
		return ArrayListIterator(this, index)
	}

	actual override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> {
		if (fromIndex < 0 || toIndex > length || fromIndex > toIndex) {
			throw IndexOutOfBoundsException("fromIndex: $fromIndex, toIndex: $toIndex, size: $length")
		}
		val vResult = ArrayList<E>(toIndex - fromIndex)
		for (vIndex in fromIndex until toIndex) vResult.add(array[vIndex])
		return vResult
	}

	override fun toString(): String {
		var vText = "["
		for (vIndex in 0 until length) {
			if (vIndex > 0) vText += ", "
			vText += array[vIndex]
		}
		return vText + "]"
	}

	override fun equals(other: Any?): Boolean {
		if (other === this) return true
		if (other !is List<*>) return false
		if (other.size != length) return false
		for (vIndex in 0 until length) {
			if (array[vIndex] != other[vIndex]) return false
		}
		return true
	}

	override fun hashCode(): Int {
		var vHash = 1
		for (vIndex in 0 until length) {
			val vElement: Any? = array[vIndex]
			vHash = 31 * vHash + (vElement?.hashCode() ?: 0)
		}
		return vHash
	}
}

private class ArrayListIterator<E>(private val list: ArrayList<E>, private var index: Int) : MutableListIterator<E> {
	private var last = -1

	override fun hasNext(): Boolean = index < list.size

	override fun next(): E {
		if (index >= list.size) throw NoSuchElementException()
		last = index
		index++
		return list[last]
	}

	override fun hasPrevious(): Boolean = index > 0

	override fun previous(): E {
		if (index <= 0) throw NoSuchElementException()
		index--
		last = index
		return list[last]
	}

	override fun nextIndex(): Int = index

	override fun previousIndex(): Int = index - 1

	override fun remove() {
		if (last < 0) throw IllegalStateException()
		list.removeAt(last)
		index = last
		last = -1
	}

	override fun set(element: E) {
		if (last < 0) throw IllegalStateException()
		list[last] = element
	}

	override fun add(element: E) {
		list.add(index, element)
		index++
		last = -1
	}
}

// ==================
// MARK: Platform functions
// ==================

public actual fun <T> listOf(element: T): List<T> {
	val vList = ArrayList<T>(1)
	vList.add(element)
	return vList
}

public fun <T> Array<out T>.asList(): List<T> {
	val vList = ArrayList<T>(size)
	for (vElement in this) vList.add(vElement)
	return vList
}

@kotlin.internal.InlineOnly
internal actual inline fun <T> Array<out T>.asArrayList(): ArrayList<T> = ArrayList(this.asList())

@PublishedApi
@kotlin.internal.InlineOnly
internal actual inline fun <E> buildListInternal(builderAction: MutableList<E>.() -> Unit): List<E> {
	val vList = ArrayList<E>()
	vList.builderAction()
	return vList
}

@PublishedApi
@kotlin.internal.InlineOnly
internal actual inline fun <E> buildListInternal(capacity: Int, builderAction: MutableList<E>.() -> Unit): List<E> {
	val vList = ArrayList<E>(capacity)
	vList.builderAction()
	return vList
}

@PublishedApi
internal actual fun checkIndexOverflow(index: Int): Int {
	if (index < 0) throw ArithmeticException("Index overflow has happened.")
	return index
}

@PublishedApi
internal actual fun checkCountOverflow(count: Int): Int {
	if (count < 0) throw ArithmeticException("Count overflow has happened.")
	return count
}

internal actual fun <T> terminateCollectionToArray(collectionSize: Int, array: Array<T>): Array<T> = array

public actual fun <T> MutableList<T>.reverse() {
	var vLow = 0
	var vHigh = size - 1
	while (vLow < vHigh) {
		val vTemp = this[vLow]
		this[vLow] = this[vHigh]
		this[vHigh] = vTemp
		vLow++
		vHigh--
	}
}
