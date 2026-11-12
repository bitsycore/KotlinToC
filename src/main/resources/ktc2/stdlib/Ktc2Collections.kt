@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_WITHOUT_EXPECT", "NOTHING_TO_INLINE", "UNCHECKED_CAST")

package kotlin.collections

import ktc2.internal.Storage
import ktc2.internal.newArray

/*
ktc2 platform layer for kotlin.collections: the declarations the common standard library
leaves to its platform (`expect`), and the collection classes the JVM takes from java.util.
*/

// ==================
// MARK: ArrayList
// ==================

/**
 * A resizable list over a typed storage block: an `ArrayList<Int>` stores plain ints.
 */
public actual class ArrayList<E> actual constructor(initialCapacity: Int) : MutableList<E>, RandomAccess {

	private var array: Storage<E> = Storage(if (initialCapacity > 0) initialCapacity else 10)
	private var length: Int = 0

	/**
	 * How many times the list changed size or storage, as `modCount` on the JVM: an iterator
	 * that finds another number than when it started throws `ConcurrentModificationException`.
	 */
	internal var changes: Int = 0

	/** The element at [index], for the iterator, which has compared the index with the size already. */
	internal fun held(index: Int): E = array[index]

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
		changes++
		var vCapacity = array.size * 2
		if (vCapacity < minCapacity) vCapacity = minCapacity
		val vBigger = Storage<E>(vCapacity)
		for (vIndex in 0 until length) vBigger[vIndex] = array[vIndex]
		array = vBigger
	}

	public actual fun trimToSize() {
		changes++
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
		changes++
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
		changes++
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
		array.drop(length)
		changes++
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
		for (vIndex in vKept until length) array.drop(vIndex)
		length = vKept
		if (vChanged) changes++
		return vChanged
	}

	actual override fun removeAll(elements: Collection<E>): Boolean = filterInPlace(elements, false)

	actual override fun retainAll(elements: Collection<E>): Boolean = filterInPlace(elements, true)

	actual override fun clear() {
		for (vIndex in 0 until length) array.drop(vIndex)
		length = 0
		changes++
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

	actual override fun iterator(): MutableIterator<E> = ArrayListCursor(this, 0)

	actual override fun listIterator(): MutableListIterator<E> = ArrayListCursor(this, 0)

	actual override fun listIterator(index: Int): MutableListIterator<E> {
		checkPosition(index)
		return ArrayListCursor(this, index)
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

/**
 * The iterator of an [ArrayList]. As on the JVM it fails with `ConcurrentModificationException`
 * when the list was changed otherwise than through it, and tests that before anything else;
 * `hasNext` does not test, and compares with the size as the JVM does (so removing the last
 * but one element inside a `for` ends the loop without an exception, there as here).
 */
private class ArrayListCursor<E>(private val list: ArrayList<E>, private var index: Int) : MutableListIterator<E> {
	private var last = -1
	private var expected = list.changes

	private fun check() {
		if (list.changes != expected) throw ConcurrentModificationException()
	}

	override fun hasNext(): Boolean = index != list.size

	override fun next(): E {
		check()
		if (index >= list.size) throw NoSuchElementException()
		last = index
		index++
		return list.held(last)
	}

	override fun hasPrevious(): Boolean = index != 0

	override fun previous(): E {
		check()
		if (index <= 0) throw NoSuchElementException()
		index--
		last = index
		return list.held(last)
	}

	override fun nextIndex(): Int = index

	override fun previousIndex(): Int = index - 1

	override fun remove() {
		if (last < 0) throw IllegalStateException()
		check()
		list.removeAt(last)
		index = last
		last = -1
		expected = list.changes
	}

	override fun set(element: E) {
		if (last < 0) throw IllegalStateException()
		check()
		list[last] = element
	}

	override fun add(element: E) {
		check()
		list.add(index, element)
		index++
		last = -1
		expected = list.changes
	}
}

/** The iterator of the other lists of this file, by index. */
private class ArrayListIterator<E>(private val list: MutableList<E>, private var index: Int) : MutableListIterator<E> {
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

public actual fun <T> MutableList<T>.fill(value: T) {
	for (vIndex in 0 until size) this[vIndex] = value
}

/** Shuffles with a fixed-seed generator: deterministic, and not the sequence the JVM produces. */
public actual fun <T> MutableList<T>.shuffle() {
	var vState = 0x2545F491
	var vIndex = size - 1
	while (vIndex > 0) {
		vState = vState xor (vState shl 13)
		vState = vState xor (vState ushr 17)
		vState = vState xor (vState shl 5)
		val vOther = (vState and 0x7FFFFFFF) % (vIndex + 1)
		val vTemp = this[vIndex]
		this[vIndex] = this[vOther]
		this[vOther] = vTemp
		vIndex--
	}
}

public actual fun <T> Iterable<T>.shuffled(): List<T> {
	val vList = ArrayList<T>()
	for (vElement in this) vList.add(vElement)
	vList.shuffle()
	return vList
}

private class NaturalOrder<T : Comparable<T>> : Comparator<T> {
	override fun compare(a: T, b: T): Int = a.compareTo(b)
}

/** A stable bottom-up merge sort. */
private fun <T> mergeSort(list: MutableList<T>, comparator: Comparator<in T>) {
	val vSize = list.size
	if (vSize < 2) return
	var vSource = ArrayList<T>(list)
	var vTarget = ArrayList<T>(list)
	var vWidth = 1
	while (vWidth < vSize) {
		var vStart = 0
		while (vStart < vSize) {
			val vMiddle = if (vStart + vWidth < vSize) vStart + vWidth else vSize
			val vEnd = if (vStart + 2 * vWidth < vSize) vStart + 2 * vWidth else vSize
			var vLeft = vStart
			var vRight = vMiddle
			var vOut = vStart
			while (vLeft < vMiddle && vRight < vEnd) {
				if (comparator.compare(vSource[vRight], vSource[vLeft]) < 0) {
					vTarget[vOut] = vSource[vRight]
					vRight++
				} else {
					vTarget[vOut] = vSource[vLeft]
					vLeft++
				}
				vOut++
			}
			while (vLeft < vMiddle) {
				vTarget[vOut] = vSource[vLeft]
				vLeft++
				vOut++
			}
			while (vRight < vEnd) {
				vTarget[vOut] = vSource[vRight]
				vRight++
				vOut++
			}
			vStart += 2 * vWidth
		}
		val vSwap = vSource
		vSource = vTarget
		vTarget = vSwap
		vWidth *= 2
	}
	for (vIndex in 0 until vSize) list[vIndex] = vSource[vIndex]
}

public actual fun <T : Comparable<T>> MutableList<T>.sort() {
	sortWith(NaturalOrder<T>())
}

public actual fun <T> MutableList<T>.sortWith(comparator: Comparator<in T>) {
	// As on the JVM a list of one element is left alone, and sorting counts as a change for the iterators.
	if (size <= 1) return
	mergeSort(this, comparator)
	if (this is ArrayList<T>) changes++
}

public actual fun <T, K> Grouping<T, K>.eachCount(): Map<K, Int> {
	val vCounts = LinkedHashMap<K, Int>()
	val vIterator = sourceIterator()
	while (vIterator.hasNext()) {
		val vKey = keyOf(vIterator.next())
		vCounts.put(vKey, (vCounts.get(vKey) ?: 0) + 1)
	}
	return vCounts
}

internal actual fun collectionToArray(collection: Collection<*>): Array<Any?> {
	val vArray = arrayOfNulls<Any?>(collection.size)
	var vIndex = 0
	for (vElement in collection) {
		vArray[vIndex] = vElement
		vIndex++
	}
	return vArray
}

internal actual fun <T> collectionToArray(collection: Collection<*>, array: Array<T>): Array<T> {
	val vTarget = if (array.size >= collection.size) array else newArray<T>(collection.size)
	var vIndex = 0
	for (vElement in collection) {
		vTarget[vIndex] = vElement as T
		vIndex++
	}
	return vTarget
}

internal actual fun <T> arrayOfNulls(reference: Array<T>, size: Int): Array<T> = newArray(size)

internal actual fun <K, V> Map<K, V>.toSingletonMapOrSelf(): Map<K, V> = this

internal actual fun <K, V> Map<out K, V>.toSingletonMap(): Map<K, V> = LinkedHashMap<K, V>(this)

internal actual fun <T> Array<out T>.copyToArrayOfAny(isVarargs: Boolean): Array<out Any?> {
	val vCopy = arrayOfNulls<Any?>(size)
	for (vIndex in 0 until size) vCopy[vIndex] = this[vIndex]
	return vCopy
}

public actual fun <K, V> mapOf(pair: Pair<K, V>): Map<K, V> {
	val vMap = LinkedHashMap<K, V>(1)
	vMap.put(pair.first, pair.second)
	return vMap
}

@PublishedApi
@kotlin.internal.InlineOnly
internal actual inline fun <K, V> buildMapInternal(builderAction: MutableMap<K, V>.() -> Unit): Map<K, V> {
	val vMap = LinkedHashMap<K, V>()
	vMap.builderAction()
	return vMap
}

@PublishedApi
@kotlin.internal.InlineOnly
internal actual inline fun <K, V> buildMapInternal(capacity: Int, builderAction: MutableMap<K, V>.() -> Unit): Map<K, V> {
	val vMap = LinkedHashMap<K, V>(capacity)
	vMap.builderAction()
	return vMap
}

@PublishedApi
internal actual fun mapCapacity(expectedSize: Int): Int = if (expectedSize < 8) 8 else expectedSize

public actual fun <T> setOf(element: T): Set<T> {
	val vSet = LinkedHashSet<T>(1)
	vSet.add(element)
	return vSet
}

@PublishedApi
@kotlin.internal.InlineOnly
internal actual inline fun <E> buildSetInternal(builderAction: MutableSet<E>.() -> Unit): Set<E> {
	val vSet = LinkedHashSet<E>()
	vSet.builderAction()
	return vSet
}

@PublishedApi
@kotlin.internal.InlineOnly
internal actual inline fun <E> buildSetInternal(capacity: Int, builderAction: MutableSet<E>.() -> Unit): Set<E> {
	val vSet = LinkedHashSet<E>(capacity)
	vSet.builderAction()
	return vSet
}

public actual inline fun <reified T> Array<out T>?.orEmpty(): Array<out T> = this ?: emptyArray<T>()

public actual inline fun <reified T> Collection<T>.toTypedArray(): Array<T> {
	val vArray = newArray<T>(size)
	var vIndex = 0
	for (vElement in this) {
		vArray[vIndex] = vElement
		vIndex++
	}
	return vArray
}

// ==================
// MARK: Base classes
// ==================

public actual interface RandomAccess

public actual abstract class AbstractMutableCollection<E> protected actual constructor() : MutableCollection<E> {
	actual abstract override val size: Int
	actual abstract override fun iterator(): MutableIterator<E>
	actual abstract override fun add(element: E): Boolean

	actual override fun isEmpty(): Boolean = size == 0

	actual override fun contains(element: E): Boolean {
		for (vElement in this) {
			if (vElement == element) return true
		}
		return false
	}

	actual override fun containsAll(elements: Collection<E>): Boolean {
		for (vElement in elements) {
			if (!contains(vElement)) return false
		}
		return true
	}

	actual override fun addAll(elements: Collection<E>): Boolean {
		var vChanged = false
		for (vElement in elements) {
			if (add(vElement)) vChanged = true
		}
		return vChanged
	}

	actual override fun remove(element: E): Boolean {
		val vIterator = iterator()
		while (vIterator.hasNext()) {
			if (vIterator.next() == element) {
				vIterator.remove()
				return true
			}
		}
		return false
	}

	/** Removes the elements whose membership in [elements] differs from [keep]. */
	private fun filterInPlace(elements: Collection<E>, keep: Boolean): Boolean {
		var vChanged = false
		val vIterator = iterator()
		while (vIterator.hasNext()) {
			if (elements.contains(vIterator.next()) != keep) {
				vIterator.remove()
				vChanged = true
			}
		}
		return vChanged
	}

	actual override fun removeAll(elements: Collection<E>): Boolean = filterInPlace(elements, false)

	actual override fun retainAll(elements: Collection<E>): Boolean = filterInPlace(elements, true)

	actual override fun clear() {
		val vIterator = iterator()
		while (vIterator.hasNext()) {
			vIterator.next()
			vIterator.remove()
		}
	}

	override fun toString(): String {
		var vText = "["
		var vFirst = true
		for (vElement in this) {
			if (!vFirst) vText += ", "
			vFirst = false
			vText += vElement
		}
		return vText + "]"
	}
}

public actual abstract class AbstractMutableSet<E> protected actual constructor() : AbstractMutableCollection<E>(), MutableSet<E> {
	actual abstract override val size: Int
	actual abstract override fun iterator(): MutableIterator<E>
	actual abstract override fun add(element: E): Boolean

	actual override fun isEmpty(): Boolean = super.isEmpty()
	actual override fun contains(element: E): Boolean = super.contains(element)
	actual override fun containsAll(elements: Collection<E>): Boolean = super.containsAll(elements)
	actual override fun addAll(elements: Collection<E>): Boolean = super.addAll(elements)
	actual override fun remove(element: E): Boolean = super.remove(element)
	actual override fun removeAll(elements: Collection<E>): Boolean = super.removeAll(elements)
	actual override fun retainAll(elements: Collection<E>): Boolean = super.retainAll(elements)
	actual override fun clear(): Unit = super.clear()

	override fun equals(other: Any?): Boolean {
		if (other === this) return true
		if (other !is Set<*>) return false
		if (other.size != size) return false
		for (vElement in this) {
			if (!other.contains(vElement)) return false
		}
		return true
	}

	override fun hashCode(): Int {
		var vHash = 0
		for (vElement in this) vHash += vElement?.hashCode() ?: 0
		return vHash
	}
}

public actual abstract class AbstractMutableList<E> protected actual constructor() : AbstractMutableCollection<E>(), MutableList<E> {
	protected actual var modCount: Int = 0

	protected actual open fun removeRange(fromIndex: Int, toIndex: Int) {
		var vCount = toIndex - fromIndex
		while (vCount > 0) {
			removeAt(fromIndex)
			vCount--
		}
	}

	actual override fun isEmpty(): Boolean = size == 0

	actual override fun contains(element: E): Boolean = indexOf(element) >= 0

	actual override fun containsAll(elements: Collection<E>): Boolean = super.containsAll(elements)

	actual override fun indexOf(element: E): Int {
		for (vIndex in 0 until size) {
			if (this[vIndex] == element) return vIndex
		}
		return -1
	}

	actual override fun lastIndexOf(element: E): Int {
		var vIndex = size - 1
		while (vIndex >= 0) {
			if (this[vIndex] == element) return vIndex
			vIndex--
		}
		return -1
	}

	actual override fun iterator(): MutableIterator<E> = ArrayListIterator(this, 0)

	actual override fun add(element: E): Boolean {
		add(size, element)
		return true
	}

	actual override fun remove(element: E): Boolean {
		val vIndex = indexOf(element)
		if (vIndex < 0) return false
		removeAt(vIndex)
		return true
	}

	actual override fun addAll(elements: Collection<E>): Boolean = addAll(size, elements)

	actual override fun addAll(index: Int, elements: Collection<E>): Boolean {
		var vAt = index
		for (vElement in elements) {
			add(vAt, vElement)
			vAt++
		}
		return !elements.isEmpty()
	}

	actual override fun removeAll(elements: Collection<E>): Boolean = super.removeAll(elements)

	actual override fun retainAll(elements: Collection<E>): Boolean = super.retainAll(elements)

	actual override fun clear() {
		removeRange(0, size)
	}

	actual override fun listIterator(): MutableListIterator<E> = ArrayListIterator(this, 0)

	actual override fun listIterator(index: Int): MutableListIterator<E> = ArrayListIterator(this, index)

	actual override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> {
		val vResult = ArrayList<E>(toIndex - fromIndex)
		for (vIndex in fromIndex until toIndex) vResult.add(this[vIndex])
		return vResult
	}

	override fun equals(other: Any?): Boolean {
		if (other === this) return true
		if (other !is List<*>) return false
		if (other.size != size) return false
		for (vIndex in 0 until size) {
			if (this[vIndex] != other[vIndex]) return false
		}
		return true
	}

	override fun hashCode(): Int {
		var vHash = 1
		for (vIndex in 0 until size) vHash = 31 * vHash + (this[vIndex]?.hashCode() ?: 0)
		return vHash
	}
}

public actual abstract class AbstractMutableMap<K, V> protected actual constructor() : MutableMap<K, V> {
	actual abstract override fun put(key: K, value: V): V?
	actual abstract override val entries: MutableSet<MutableMap.MutableEntry<K, V>>

	actual override val size: Int get() = entries.size

	actual override fun isEmpty(): Boolean = size == 0

	actual override fun clear() {
		entries.clear()
	}

	actual override fun containsKey(key: K): Boolean {
		for (vEntry in entries) {
			if (vEntry.key == key) return true
		}
		return false
	}

	actual override fun containsValue(value: V): Boolean {
		for (vEntry in entries) {
			if (vEntry.value == value) return true
		}
		return false
	}

	actual override fun get(key: K): V? {
		for (vEntry in entries) {
			if (vEntry.key == key) return vEntry.value
		}
		return null
	}

	actual override fun putAll(from: Map<out K, V>) {
		for (vEntry in from.entries) put(vEntry.key, vEntry.value)
	}

	actual override fun remove(key: K): V? {
		val vIterator = entries.iterator()
		while (vIterator.hasNext()) {
			val vEntry = vIterator.next()
			if (vEntry.key == key) {
				val vValue = vEntry.value
				vIterator.remove()
				return vValue
			}
		}
		return null
	}

	actual override val keys: MutableSet<K>
		get() {
			val vKeys = LinkedHashSet<K>()
			for (vEntry in entries) vKeys.add(vEntry.key)
			return vKeys
		}

	actual override val values: MutableCollection<V>
		get() {
			val vValues = ArrayList<V>()
			for (vEntry in entries) vValues.add(vEntry.value)
			return vValues
		}
}
