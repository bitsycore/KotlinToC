/** A list of numbers is a list of objects too, and may be asked about objects that are no numbers. */
fun <T> ask(list: List<T>, other: Collection<T>): Boolean = list.containsAll(other)

fun main() {
	val longs = listOf(2L, 3L, Long.MAX_VALUE)
	val views: List<List<Any?>> = listOf(longs, longs.asReversed(), longs.reversed(), longs.subList(0, 2), longs.toMutableList())
	for (view in views) {
		println(view.containsAll(listOf<Any?>("x")))
		println(view.containsAll(listOf<Any?>(null)))
		println(view.containsAll(listOf<Any?>(2L, 3L)))
		println(view.containsAll(listOf<Any?>(2, 3L)))
		println(view.contains("x"))
		println(view.indexOf(null))
		println(view.lastIndexOf(3L))
	}
	println(ask<Any?>(longs, setOf("a", 2L)))
	println(ask(longs, setOf(3L, 2L)))
	val set: Set<Any?> = setOf(1, 2, 3)
	println(set.containsAll(listOf<Any?>(1, "one")))
	println(set.containsAll(listOf<Any?>(1, 3)))
	more()
}

/** A number asked a member of `Any` through `Comparable`; an array as an object; an unsigned vararg given nothing. */
data class Sortable<K : Comparable<K>>(val key: K, val index: Int) : Comparable<Sortable<K>> {
	override fun compareTo(other: Sortable<K>): Int = this.key.compareTo(other.key)
}

fun more() {
	val a = Sortable('A', 1)
	println(a.hashCode() == Sortable('A', 1).hashCode())
	println(Sortable(2.5, 1).hashCode() == Sortable(2.5, 1).hashCode())
	println(a == Sortable('A', 1))
	println(Sortable("x", 2))
	val array = arrayOf<Any>("a", "b")
	println(array.toString().isNotEmpty())
	println(array.hashCode() == array.hashCode())
	println(array.equals(array))
	val [ints, chars] = arrayOf(1 to 'a', 2 to 'b').unzip()
	println("$ints $chars")
	println(ubyteArrayOf().size + uintArrayOf().size)
}
