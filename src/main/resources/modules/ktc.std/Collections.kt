package ktc.std

class ListIterator<T>(val buf: Ref<Array<T>>, val size: Int) : Iterator<T> {

	var idx: Int = 0

	override operator fun hasNext(): Boolean {
		return idx < size
	}

	override operator fun next(): T {
		if (idx >= size) throw NoSuchElementException("Iterator has no next element.")
		val v = buf[idx]
		idx = idx + 1
		return v
	}
}

interface List<T> : Cloneable<List<T>> {
	val size: Int
	operator fun get(index: Int): T
	fun first(): T
	fun last(): T
	fun isEmpty(): Boolean
	operator fun contains(value: T): Boolean
	fun indexOf(value: T): Int
	operator fun iterator(): ListIterator<T>
}

interface MutableList<T> : List<T> {
	fun add(value: T)
	operator fun set(index: Int, value: T)
	fun removeAt(index: Int): T
	fun clear()
}

interface Cloneable<T> {
	fun clone(): Ref<T>
	fun cloneWith(inAllocator: Ref<Allocator>): Ref<T>
}

class ArrayList<T>(private val allocator: Ref<Allocator>, capacity: Int) : MutableList<T> {

	private var buf: Ref<Array<T>> = Array<T>(if (capacity > 0) capacity else 4).allocWith(allocator)!!

	override var size: Int = 0
		private set

    override fun add(value: T) {
        if (size >= buf.size) {
            val newSize = if (buf.size > 0) buf.size * 2 else 4
            buf = buf.resizeWith(allocator, newSize)
        }
        buf[size] = value
        size = size + 1
    }

	override operator fun get(index: Int): T {
		if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index $index out of bounds for length $size")
		return buf[index]
	}

	override operator fun set(index: Int, value: T) {
		if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index $index out of bounds for length $size")
		buf[index] = value
	}

	override fun first(): T {
		if (size == 0) throw NoSuchElementException("List is empty.")
		return buf[0]
	}

	override fun last(): T {
		if (size == 0) throw NoSuchElementException("List is empty.")
		return buf[size - 1]
	}

	override fun isEmpty(): Boolean = size == 0

	/* Deep clone using this list's OWN allocator. See cloneWith for the details / rationale. */
	override fun clone(): Ref<ArrayList<T>> = cloneWith(allocator)

	/* Deep clone into [inAllocator] - a NEW heap-allocated list (Ref<ArrayList<T>>) with its OWN backing
	   buffer in that allocator, holding the same elements. Distinct from the synthesized struct .copy(),
	   which copies only {allocator, buf, size} and SHARES this list's buffer; cloneWith bulk-copies the
	   backing array so the two lists are fully independent. Named clone/cloneWith (not copy): copy() is the
	   shallow struct copy; clone is the deep one. clone() reuses this list's allocator, cloneWith(a) targets
	   a different one (e.g. an Arena). Caller owns the result - freeMem(it) + it.dispose() when done. */
	override fun cloneWith(inAllocator: Ref<Allocator>): Ref<ArrayList<T>> {
		val vResult = ArrayList<T>(inAllocator, if (size > 0) size else 1).allocWith(inAllocator)!!
		buf.copyInto(vResult.buf, 0, 0, size)   // memcpy our elements INTO the ctor's buffer - one alloc, no free
		vResult.size = size
		return vResult
	}

	override fun removeAt(index: Int): T {
		if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index $index out of bounds for length $size")
		val removed = buf[index]
		for (i in index until size - 1) {
			buf[i] = buf[i + 1]
		}
		size = size - 1
		return removed
	}

	override operator fun contains(value: T): Boolean {
		for (i in 0 until size) {
			if (buf[i] == value) return true
		}
		return false
	}

	override fun indexOf(value: T): Int {
		for (i in 0 until size) {
			if (buf[i] == value) return i
		}
		return -1
	}

	override fun clear() {
		size = 0
	}

	override operator fun iterator(): ListIterator<T> {
		return ListIterator<T>(buf, size)
	}

    override fun dispose() {
		allocator.freeMem(buf)
    }

}

fun <T> mutableListOf(allocator: Ref<Allocator>, vararg items: T): MutableList<T> {
    val list = ArrayList<T>(allocator, items.size)
    for (item in items) {
        list.add(item)
    }
    return list
}

fun <T> listOf(allocator: Ref<Allocator>, vararg items: T): List<T> {
    val list = ArrayList<T>(allocator, items.size)
    for (item in items) {
        list.add(item)
    }
    return list
}