package ktc.std

class ListIterator<T>(val buf: @Ptr Array<T>, val size: Int) : Iterator<T> {

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

interface List<T> {
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

class ArrayList<T>(private val allocator: @Ptr Allocator, capacity: Int) : MutableList<T> {

	private var buf: @Ptr Array<T> = Array<T>(if (capacity > 0) capacity else 4).allocWith(allocator)!!

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

fun <T> mutableListOf(allocator: @Ptr Allocator, vararg items: T): MutableList<T> {
    val list = ArrayList<T>(allocator, items.size)
    for (item in items) {
        list.add(item)
    }
    return list
}

fun <T> listOf(allocator: @Ptr Allocator, vararg items: T): List<T> {
    val list = ArrayList<T>(allocator, items.size)
    for (item in items) {
        list.add(item)
    }
    return list
}