package ktc.std

class ListIterator<T>(val buf: Ref<Array<T>>, val size: Int) : Iterator<T> {

	var idx: Int = 0

	override operator fun hasNext(): Boolean {
		return idx < size
	}

	override operator fun next(): T {
		val v = buf[idx]
		idx = idx + 1
		return v
	}
}

interface List<T> : Cloneable<List<T>> {
	val size: Int
	operator fun get(index: Int): T
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

/* Deep-copy contract. F-bounded self-type: an implementer says `Cloneable<Self>`, so clone/cloneWith
   return a Ref to its OWN concrete type (Ref<ArrayList<T>>, Ref<HashMap<K,V>>), not an erased interface
   ref. These are the DEEP copy (independent backing storage), unlike the shallow synthesized .copy().
   clone() reuses the implementer's own allocator; cloneWith(a) clones into a different one. */
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

	override operator fun get(index: Int): T = buf[index]

	override operator fun set(index: Int, value: T) {
		buf[index] = value
	}

	/* Deep clone using this list's OWN allocator. See cloneWith for the details / rationale. */
	override fun clone(): Ref<ArrayList<T>> = cloneWith(allocator)

	/* Deep clone into [inAllocator] — a NEW heap-allocated list (Ref<ArrayList<T>>) with its OWN backing
	   buffer in that allocator, holding the same elements. Distinct from the synthesized struct .copy(),
	   which copies only {allocator, buf, size} and SHARES this list's buffer; cloneWith bulk-copies the
	   backing array so the two lists are fully independent. Named clone/cloneWith (not copy): copy() is the
	   shallow struct copy; clone is the deep one. clone() reuses this list's allocator, cloneWith(a) targets
	   a different one (e.g. an Arena). Caller owns the result — freeMem(it) + it.dispose() when done. */
	override fun cloneWith(inAllocator: Ref<Allocator>): Ref<ArrayList<T>> {
		val vResult = ArrayList<T>(inAllocator, if (size > 0) size else 1).allocWith(inAllocator)!!
		vResult.dispose()                          // free the ctor's initial buffer (we replace it)
		vResult.buf  = buf.copyWith(inAllocator)   // bulk memcpy of the backing array (O(n), no per-element add)
		vResult.size = size
		return vResult
	}

	override fun removeAt(index: Int): T {
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