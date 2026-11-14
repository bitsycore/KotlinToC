@file:Suppress("UNCHECKED_CAST")

package kotlin.collections

/*
ktc2 platform layer: what `buildList`, `buildSet` and `buildMap` build. As on the JVM and on Kotlin/Native,
the collection is the one the builder action filled, and refuses every change once it is built, through itself,
its views, its iterators and its parts; an empty one is one shared object.
*/

private fun refuse(): Nothing = throw UnsupportedOperationException()

// ==================
// MARK: List
// ==================

@PublishedApi
internal class ListBuilder<E>(capacity: Int) : AbstractMutableList<E>(), RandomAccess {
	private val backing = ArrayList<E>(capacity)
	private var isReadOnly = false

	internal fun checkIsMutable() {
		if (isReadOnly) refuse()
	}

	/** Ends the building: this list, or the shared empty one, now refuses every change. */
	fun build(): List<E> {
		checkIsMutable()
		isReadOnly = true
		return if (backing.isEmpty()) gEmptyBuiltList as List<E> else this
	}

	internal fun markBuilt() {
		isReadOnly = true
	}

	override val size: Int get() = backing.size

	override fun get(index: Int): E = backing[index]

	override fun set(index: Int, element: E): E {
		checkIsMutable()
		return backing.set(index, element)
	}

	override fun add(element: E): Boolean {
		checkIsMutable()
		modCount++
		return backing.add(element)
	}

	override fun add(index: Int, element: E) {
		checkIsMutable()
		modCount++
		backing.add(index, element)
	}

	override fun addAll(elements: Collection<E>): Boolean {
		checkIsMutable()
		modCount++
		return backing.addAll(elements)
	}

	override fun addAll(index: Int, elements: Collection<E>): Boolean {
		checkIsMutable()
		AbstractList.checkPositionIndex(index, backing.size)
		modCount++
		return backing.addAll(index, elements)
	}

	override fun removeAt(index: Int): E {
		checkIsMutable()
		modCount++
		return backing.removeAt(index)
	}

	override fun remove(element: E): Boolean {
		checkIsMutable()
		return super.remove(element)
	}

	override fun removeAll(elements: Collection<E>): Boolean {
		checkIsMutable()
		return super.removeAll(elements)
	}

	override fun retainAll(elements: Collection<E>): Boolean {
		checkIsMutable()
		return super.retainAll(elements)
	}

	override fun clear() {
		checkIsMutable()
		modCount++
		backing.clear()
	}
}

/** The list every `buildList` that adds nothing gives. */
private val gEmptyBuiltList: List<Any?> = ListBuilder<Any?>(0).also { it.markBuilt() }

// ==================
// MARK: Set
// ==================

@PublishedApi
internal class SetBuilder<E>(capacity: Int) : AbstractMutableSet<E>() {
	private val backing = LinkedHashSet<E>(capacity)
	private var isReadOnly = false

	fun checkIsMutable() {
		if (isReadOnly) refuse()
	}

	fun build(): Set<E> {
		checkIsMutable()
		isReadOnly = true
		return if (backing.isEmpty()) gEmptyBuiltSet as Set<E> else this
	}

	internal fun markBuilt() {
		isReadOnly = true
	}

	override val size: Int get() = backing.size
	override fun contains(element: E): Boolean = backing.contains(element)
	override fun iterator(): MutableIterator<E> = CheckedIterator(backing.iterator(), this::checkIsMutable)

	override fun add(element: E): Boolean {
		checkIsMutable()
		return backing.add(element)
	}

	override fun addAll(elements: Collection<E>): Boolean {
		checkIsMutable()
		return backing.addAll(elements)
	}

	override fun remove(element: E): Boolean {
		checkIsMutable()
		return backing.remove(element)
	}

	override fun removeAll(elements: Collection<E>): Boolean {
		checkIsMutable()
		return backing.removeAll(elements)
	}

	override fun retainAll(elements: Collection<E>): Boolean {
		checkIsMutable()
		return backing.retainAll(elements)
	}

	override fun clear() {
		checkIsMutable()
		backing.clear()
	}
}

private val gEmptyBuiltSet: Set<Any?> = SetBuilder<Any?>(0).also { it.markBuilt() }

/** An iterator that asks [check] before it removes anything. */
private class CheckedIterator<E>(private val inner: MutableIterator<E>, private val check: () -> Unit) : MutableIterator<E> {
	override fun hasNext(): Boolean = inner.hasNext()
	override fun next(): E = inner.next()

	override fun remove() {
		check()
		inner.remove()
	}
}

// ==================
// MARK: Map
// ==================

@PublishedApi
internal class MapBuilder<K, V>(capacity: Int) : AbstractMutableMap<K, V>() {
	private val backing = LinkedHashMap<K, V>(capacity)
	private var isReadOnly = false

	/** How many times a key was added or removed: an entry handed out before fails after, as on the JVM. */
	internal var changes = 0

	fun checkIsMutable() {
		if (isReadOnly) refuse()
	}

	fun build(): Map<K, V> {
		checkIsMutable()
		isReadOnly = true
		return if (backing.isEmpty()) gEmptyBuiltMap as Map<K, V> else this
	}

	internal fun markBuilt() {
		isReadOnly = true
	}

	override val size: Int get() = backing.size
	override fun isEmpty(): Boolean = backing.isEmpty()
	override fun containsKey(key: K): Boolean = backing.containsKey(key)
	override fun containsValue(value: V): Boolean = backing.containsValue(value)
	override fun get(key: K): V? = backing[key]

	override fun put(key: K, value: V): V? {
		checkIsMutable()
		if (!backing.containsKey(key)) changes++
		return backing.put(key, value)
	}

	override fun putAll(from: Map<out K, V>) {
		checkIsMutable()
		for (vEntry in from.entries) put(vEntry.key, vEntry.value)
	}

	override fun remove(key: K): V? {
		checkIsMutable()
		if (backing.containsKey(key)) changes++
		return backing.remove(key)
	}

	override fun clear() {
		checkIsMutable()
		if (backing.isNotEmpty()) changes++
		backing.clear()
	}

	override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = BuilderEntries(this, backing.entries)
	override val keys: MutableSet<K> get() = BuilderView(this, backing.keys) as MutableSet<K>
	override val values: MutableCollection<V> get() = BuilderValues(this, backing.values)
}

private val gEmptyBuiltMap: Map<Any?, Any?> = MapBuilder<Any?, Any?>(0).also { it.markBuilt() }

/** The keys of a map being built (or built): every change asks the map first. */
private class BuilderView<E>(private val map: MapBuilder<*, *>, private val inner: MutableSet<E>) : AbstractMutableSet<E>() {
	override val size: Int get() = inner.size
	override fun contains(element: E): Boolean = inner.contains(element)
	override fun iterator(): MutableIterator<E> = CheckedIterator(inner.iterator(), map::checkIsMutable)
	override fun add(element: E): Boolean = refuse()

	override fun addAll(elements: Collection<E>): Boolean = refuse()

	override fun remove(element: E): Boolean {
		map.checkIsMutable()
		return inner.remove(element)
	}

	override fun removeAll(elements: Collection<E>): Boolean {
		map.checkIsMutable()
		return inner.removeAll(elements)
	}

	override fun retainAll(elements: Collection<E>): Boolean {
		map.checkIsMutable()
		return inner.retainAll(elements)
	}

	override fun clear() {
		map.checkIsMutable()
		inner.clear()
	}
}

/** The values of a map being built (or built). */
private class BuilderValues<V>(private val map: MapBuilder<*, *>, private val inner: MutableCollection<V>) : AbstractMutableCollection<V>() {
	override val size: Int get() = inner.size
	override fun contains(element: V): Boolean = inner.contains(element)
	override fun iterator(): MutableIterator<V> = CheckedIterator(inner.iterator(), map::checkIsMutable)
	override fun add(element: V): Boolean = refuse()

	override fun addAll(elements: Collection<V>): Boolean = refuse()

	override fun remove(element: V): Boolean {
		map.checkIsMutable()
		return inner.remove(element)
	}

	override fun removeAll(elements: Collection<V>): Boolean {
		map.checkIsMutable()
		return inner.removeAll(elements)
	}

	override fun retainAll(elements: Collection<V>): Boolean {
		map.checkIsMutable()
		return inner.retainAll(elements)
	}

	override fun clear() {
		map.checkIsMutable()
		inner.clear()
	}
}

/** The entries of a map being built (or built): an entry's `setValue` asks the map too. */
private class BuilderEntries<K, V>(private val map: MapBuilder<K, V>, private val inner: MutableSet<MutableMap.MutableEntry<K, V>>) : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
	override val size: Int get() = inner.size
	override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean = inner.contains(element)
	override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = BuilderEntryIterator(map, inner.iterator())
	override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = refuse()

	override fun addAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean = refuse()

	override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
		map.checkIsMutable()
		return inner.remove(element)
	}

	override fun removeAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
		map.checkIsMutable()
		return inner.removeAll(elements)
	}

	override fun retainAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
		map.checkIsMutable()
		return inner.retainAll(elements)
	}

	override fun clear() {
		map.checkIsMutable()
		inner.clear()
	}
}

private class BuilderEntryIterator<K, V>(private val map: MapBuilder<K, V>, private val inner: MutableIterator<MutableMap.MutableEntry<K, V>>) : MutableIterator<MutableMap.MutableEntry<K, V>> {
	override fun hasNext(): Boolean = inner.hasNext()
	override fun next(): MutableMap.MutableEntry<K, V> = BuilderEntry(map, inner.next())

	override fun remove() {
		map.checkIsMutable()
		inner.remove()
	}
}

private class BuilderEntry<K, V>(private val map: MapBuilder<K, V>, private val inner: MutableMap.MutableEntry<K, V>) : MutableMap.MutableEntry<K, V> {
	private val seen = map.changes

	/** An entry of a map that has since gained or lost a key is no longer one of it. */
	private fun check() {
		if (map.changes != seen) throw ConcurrentModificationException("The backing map has been modified after this entry was obtained.")
	}

	override val key: K get() = inner.key.also { check() }
	override val value: V get() = inner.value.also { check() }

	override fun setValue(newValue: V): V {
		check()
		map.checkIsMutable()
		return inner.setValue(newValue)
	}

	override fun equals(other: Any?): Boolean {
		check()
		return inner == other
	}

	override fun hashCode(): Int {
		check()
		return inner.hashCode()
	}

	override fun toString(): String {
		check()
		return inner.toString()
	}
}
