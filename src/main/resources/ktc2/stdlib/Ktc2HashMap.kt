@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "NOTHING_TO_INLINE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.collections

import ktc2.internal.Storage

/*
ktc2 platform layer: the hash collections.

One implementation serves all four classes. Entries sit in parallel typed arrays in insertion
order (so a HashMap<String, Int> stores its values as plain ints) and a table of indices is
probed linearly by hash. A removed entry stays as a hole until the next rebuild. Iteration is
in insertion order, which is what LinkedHashMap requires and HashMap permits.
*/

// ==================
// MARK: HashMap
// ==================

public actual class HashMap<K, V> actual constructor(initialCapacity: Int) : MutableMap<K, V> {

	private var keyArray: Storage<K> = Storage(if (initialCapacity > 8) initialCapacity else 8)
	private var valueArray: Storage<V> = Storage(keyArray.size)
	private var hashArray: Storage<Int> = Storage(keyArray.size)
	private var liveArray: Storage<Boolean> = Storage(keyArray.size)

	/** Slot → entry index + 1, or 0 when the slot is empty. Its size is a power of two. */
	private var table: Storage<Int> = Storage(tableSizeFor(keyArray.size))

	/** Entries in use, holes included. */
	private var used: Int = 0
	private var live: Int = 0

	/** How many times a key was added or removed, as `modCount` on the JVM: see [HashMapCursor]. */
	internal var changes: Int = 0

	public actual constructor() : this(8)

	public actual constructor(initialCapacity: Int, loadFactor: Float) : this(initialCapacity)

	public actual constructor(original: Map<out K, V>) : this(original.size) {
		putAll(original)
	}

	private fun tableSizeFor(capacity: Int): Int {
		var vSize = 16
		while (vSize < capacity * 2) vSize *= 2
		return vSize
	}

	private fun hashOf(key: K): Int {
		val vHash = key?.hashCode() ?: 0
		return vHash xor (vHash ushr 16)
	}

	/** The index of the live entry for [key], or -1. */
	private fun find(key: K, hash: Int): Int {
		// The arrays as they are now, held for the whole probe. They are replaced together (`rebuild`,
		// `clear`) and a slot of the table only names entries its own arrays have, so these four are
		// read without a test - also when comparing a key runs code of the program that replaces the
		// map's: the ones held here stay what their indices were made for.
		val vTable = table
		val vLive = liveArray
		val vHashes = hashArray
		val vKeys = keyArray
		val vMask = vTable.size - 1
		var vSlot = hash and vMask
		while (true) {
			val vEntry = vTable.at(vSlot)
			if (vEntry == 0) return -1
			val vIndex = vEntry - 1
			if (vLive.at(vIndex) && vHashes.at(vIndex) == hash && vKeys.at(vIndex) == key) return vIndex
			vSlot = (vSlot + 1) and vMask
		}
	}

	private fun link(index: Int) {
		val vTable = table
		val vMask = vTable.size - 1
		var vSlot = hashArray.at(index) and vMask
		while (vTable.at(vSlot) != 0) vSlot = (vSlot + 1) and vMask
		vTable.put(vSlot, index + 1)
	}

	/** Drops the holes and makes room for at least one more entry. */
	private fun rebuild() {
		val vCapacity = if (live * 2 > keyArray.size) keyArray.size * 2 else keyArray.size
		val vKeys = Storage<K>(vCapacity)
		val vValues = Storage<V>(vCapacity)
		val vHashes = Storage<Int>(vCapacity)
		val vLive = Storage<Boolean>(vCapacity)
		var vCount = 0
		for (vIndex in 0 until used) {
			if (!liveArray[vIndex]) continue
			vKeys[vCount] = keyArray[vIndex]
			vValues[vCount] = valueArray[vIndex]
			vHashes[vCount] = hashArray[vIndex]
			vLive[vCount] = true
			vCount++
		}
		keyArray = vKeys
		valueArray = vValues
		hashArray = vHashes
		liveArray = vLive
		used = vCount
		table = Storage(tableSizeFor(vCapacity))
		for (vIndex in 0 until used) link(vIndex)
	}

	actual override val size: Int get() = live

	actual override fun isEmpty(): Boolean = live == 0

	actual override fun containsKey(key: K): Boolean = find(key, hashOf(key)) >= 0

	actual override fun containsValue(value: V): Boolean {
		for (vIndex in 0 until used) {
			if (liveArray[vIndex] && valueArray[vIndex] == value) return true
		}
		return false
	}

	actual override operator fun get(key: K): V? {
		val vIndex = find(key, hashOf(key))
		return if (vIndex < 0) null else valueArray[vIndex]
	}

	actual override fun put(key: K, value: V): V? {
		val vHash = hashOf(key)
		val vIndex = find(key, vHash)
		if (vIndex >= 0) {
			val vOld = valueArray[vIndex]
			valueArray[vIndex] = value
			return vOld
		}
		// Room for one more is made first: `used` is an index of the four arrays.
		if (used == keyArray.size) rebuild()
		keyArray.put(used, key)
		valueArray.put(used, value)
		hashArray.put(used, vHash)
		liveArray.put(used, true)
		link(used)
		used++
		live++
		changes++
		return null
	}

	actual override fun remove(key: K): V? {
		val vIndex = find(key, hashOf(key))
		if (vIndex < 0) return null
		val vOld = valueArray[vIndex]
		removeEntry(vIndex)
		// The map no longer holds them. (After a removal through an iterator the entry handed out
		// still reads its key and value, as on the JVM: that hole is emptied by the next rebuild.)
		keyArray.drop(vIndex)
		valueArray.drop(vIndex)
		return vOld
	}

	actual override fun putAll(from: Map<out K, V>) {
		for (vEntry in from.entries) put(vEntry.key, vEntry.value)
	}

	actual override fun clear() {
		keyArray = Storage(8)
		valueArray = Storage(8)
		hashArray = Storage(8)
		liveArray = Storage(8)
		table = Storage(tableSizeFor(8))
		used = 0
		live = 0
		changes++
	}

	actual override val keys: MutableSet<K> get() = HashMapKeys(this)

	actual override val values: MutableCollection<V> get() = HashMapValues(this)

	actual override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = HashMapEntries(this)

	// ============
	//  Access by entry index, for the views

	internal fun entryLimit(): Int = used

	/**
	 * The index of the first live entry at or after [from] (which is not negative), or -1. For
	 * the cursor, and for the loop ktc2 writes in its place (`ListLoops`), which finds this
	 * function and [checkChanges] by their names.
	 */
	internal fun liveFrom(from: Int): Int {
		val vLive = liveArray
		val vLimit = used
		var vIndex = from
		while (vIndex < vLimit && !vLive.at(vIndex)) vIndex++
		return if (vIndex < vLimit) vIndex else -1
	}

	/** Fails when a key was added or removed since [expected] was read from [changes]. */
	internal fun checkChanges(expected: Int) {
		if (changes != expected) throw ConcurrentModificationException()
	}

	internal fun isLiveEntry(index: Int): Boolean = liveArray[index]

	internal fun keyAt(index: Int): K = keyArray[index]

	internal fun valueAt(index: Int): V = valueArray[index]

	internal fun setValueAt(index: Int, value: V): V {
		val vOld = valueArray[index]
		valueArray[index] = value
		return vOld
	}

	internal fun removeEntry(index: Int) {
		liveArray[index] = false
		live--
		changes++
	}

	override fun toString(): String {
		var vText = "{"
		var vFirst = true
		for (vIndex in 0 until used) {
			if (!liveArray[vIndex]) continue
			if (!vFirst) vText += ", "
			vFirst = false
			vText += keyArray[vIndex]
			vText += "="
			vText += valueArray[vIndex]
		}
		return vText + "}"
	}

	override fun equals(other: Any?): Boolean {
		if (other === this) return true
		if (other !is Map<*, *>) return false
		if (other.size != live) return false
		for (vIndex in 0 until used) {
			if (!liveArray[vIndex]) continue
			val vKey: Any? = keyArray[vIndex]
			val vValue: Any? = valueArray[vIndex]
			if (other[vKey] != vValue) return false
			if (vValue == null && !other.containsKey(vKey)) return false
		}
		return true
	}

	override fun hashCode(): Int {
		var vHash = 0
		for (vIndex in 0 until used) {
			if (!liveArray[vIndex]) continue
			vHash += (keyArray[vIndex]?.hashCode() ?: 0) xor (valueArray[vIndex]?.hashCode() ?: 0)
		}
		return vHash
	}
}

/**
 * Walks the live entries of a map; [next] yields the entry index. As on the JVM the entry
 * after the one handed out is found at once, so `hasNext` answers from what the map held
 * then, and `next` fails with `ConcurrentModificationException` when a key was added or
 * removed otherwise than through this cursor.
 */
private class HashMapCursor<K, V>(private val map: HashMap<K, V>) {
	private var coming = map.liveFrom(0)
	private var last = -1
	private var expected = map.changes

	fun hasNext(): Boolean = coming >= 0

	fun next(): Int {
		map.checkChanges(expected)
		if (coming < 0) throw NoSuchElementException()
		last = coming
		coming = map.liveFrom(coming + 1)
		return last
	}

	fun remove() {
		if (last < 0) throw IllegalStateException()
		if (map.changes != expected) throw ConcurrentModificationException()
		map.removeEntry(last)
		last = -1
		expected = map.changes
	}
}

private class HashMapEntry<K, V>(private val map: HashMap<K, V>, private val index: Int) : MutableMap.MutableEntry<K, V> {
	override val key: K get() = map.keyAt(index)
	override val value: V get() = map.valueAt(index)

	override fun setValue(newValue: V): V = map.setValueAt(index, newValue)

	override fun toString(): String = "$key=$value"

	override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value

	override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
}

private class HashMapKeyIterator<K, V>(map: HashMap<K, V>) : MutableIterator<K> {
	private val owner = map
	private val cursor = HashMapCursor(map)

	override fun hasNext(): Boolean = cursor.hasNext()
	override fun next(): K = owner.keyAt(cursor.next())
	override fun remove() = cursor.remove()
}

private class HashMapValueIterator<K, V>(map: HashMap<K, V>) : MutableIterator<V> {
	private val owner = map
	private val cursor = HashMapCursor(map)

	override fun hasNext(): Boolean = cursor.hasNext()
	override fun next(): V = owner.valueAt(cursor.next())
	override fun remove() = cursor.remove()
}

private class HashMapEntryIterator<K, V>(map: HashMap<K, V>) : MutableIterator<MutableMap.MutableEntry<K, V>> {
	private val owner = map
	private val cursor = HashMapCursor(map)

	override fun hasNext(): Boolean = cursor.hasNext()
	override fun next(): MutableMap.MutableEntry<K, V> = HashMapEntry(owner, cursor.next())
	override fun remove() = cursor.remove()
}

private class HashMapKeys<K, V>(private val map: HashMap<K, V>) : AbstractMutableSet<K>() {
	override val size: Int get() = map.size
	override fun iterator(): MutableIterator<K> = HashMapKeyIterator(map)
	override fun add(element: K): Boolean = throw UnsupportedOperationException()
	override fun contains(element: K): Boolean = map.containsKey(element)

	override fun remove(element: K): Boolean {
		if (!map.containsKey(element)) return false
		map.remove(element)
		return true
	}

	override fun clear() = map.clear()
}

private class HashMapValues<K, V>(private val map: HashMap<K, V>) : AbstractMutableCollection<V>() {
	override val size: Int get() = map.size
	override fun iterator(): MutableIterator<V> = HashMapValueIterator(map)
	override fun add(element: V): Boolean = throw UnsupportedOperationException()
	override fun contains(element: V): Boolean = map.containsValue(element)
	override fun clear() = map.clear()
}

private class HashMapEntries<K, V>(private val map: HashMap<K, V>) : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
	override val size: Int get() = map.size
	override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = HashMapEntryIterator(map)
	override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()
	override fun clear() = map.clear()
}

// ==================
// MARK: LinkedHashMap
// ==================

public actual class LinkedHashMap<K, V> actual constructor(initialCapacity: Int) : MutableMap<K, V> {

	private val map = HashMap<K, V>(initialCapacity)

	public actual constructor() : this(8)

	public actual constructor(initialCapacity: Int, loadFactor: Float) : this(initialCapacity)

	public actual constructor(original: Map<out K, V>) : this(original.size) {
		putAll(original)
	}

	actual override val size: Int get() = map.size
	actual override fun isEmpty(): Boolean = map.isEmpty()
	actual override fun containsKey(key: K): Boolean = map.containsKey(key)
	actual override fun containsValue(value: V): Boolean = map.containsValue(value)
	actual override fun get(key: K): V? = map[key]
	actual override fun put(key: K, value: V): V? = map.put(key, value)
	actual override fun remove(key: K): V? = map.remove(key)
	actual override fun putAll(from: Map<out K, V>) = map.putAll(from)
	actual override fun clear() = map.clear()
	actual override val keys: MutableSet<K> get() = map.keys
	actual override val values: MutableCollection<V> get() = map.values
	actual override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = map.entries

	override fun toString(): String = map.toString()
	override fun equals(other: Any?): Boolean = map == other
	override fun hashCode(): Int = map.hashCode()
}

// ==================
// MARK: HashSet, LinkedHashSet
// ==================

public actual class HashSet<E> actual constructor(initialCapacity: Int) : MutableSet<E> {

	private val map = HashMap<E, Boolean>(initialCapacity)

	public actual constructor() : this(8)

	public actual constructor(initialCapacity: Int, loadFactor: Float) : this(initialCapacity)

	public actual constructor(elements: Collection<E>) : this(elements.size) {
		addAll(elements)
	}

	actual override val size: Int get() = map.size
	actual override fun isEmpty(): Boolean = map.isEmpty()
	actual override fun contains(element: E): Boolean = map.containsKey(element)

	actual override fun containsAll(elements: Collection<E>): Boolean = containsEach(this, elements)

	actual override fun iterator(): MutableIterator<E> = map.keys.iterator()

	actual override fun add(element: E): Boolean = map.put(element, true) == null

	actual override fun remove(element: E): Boolean = map.remove(element) != null

	actual override fun addAll(elements: Collection<E>): Boolean {
		var vChanged = false
		for (vElement in elements) {
			if (add(vElement)) vChanged = true
		}
		return vChanged
	}

	actual override fun removeAll(elements: Collection<E>): Boolean {
		var vChanged = false
		for (vElement in elements) {
			if (remove(vElement)) vChanged = true
		}
		return vChanged
	}

	actual override fun retainAll(elements: Collection<E>): Boolean {
		var vChanged = false
		val vIterator = iterator()
		while (vIterator.hasNext()) {
			if (!elements.contains(vIterator.next())) {
				vIterator.remove()
				vChanged = true
			}
		}
		return vChanged
	}

	actual override fun clear() = map.clear()

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

public actual class LinkedHashSet<E> actual constructor(initialCapacity: Int) : MutableSet<E> {

	private val set = HashSet<E>(initialCapacity)

	public actual constructor() : this(8)

	public actual constructor(initialCapacity: Int, loadFactor: Float) : this(initialCapacity)

	public actual constructor(elements: Collection<E>) : this(elements.size) {
		addAll(elements)
	}

	actual override val size: Int get() = set.size
	actual override fun isEmpty(): Boolean = set.isEmpty()
	actual override fun contains(element: E): Boolean = set.contains(element)
	actual override fun containsAll(elements: Collection<E>): Boolean = set.containsAll(elements)
	actual override fun iterator(): MutableIterator<E> = set.iterator()
	actual override fun add(element: E): Boolean = set.add(element)
	actual override fun remove(element: E): Boolean = set.remove(element)
	actual override fun addAll(elements: Collection<E>): Boolean = set.addAll(elements)
	actual override fun removeAll(elements: Collection<E>): Boolean = set.removeAll(elements)
	actual override fun retainAll(elements: Collection<E>): Boolean = set.retainAll(elements)
	actual override fun clear() = set.clear()

	override fun toString(): String = set.toString()
	override fun equals(other: Any?): Boolean = set == other
	override fun hashCode(): Int = set.hashCode()
}
