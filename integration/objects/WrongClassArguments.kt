// A value of the wrong class reaching a function through an unchecked cast: a ClassCastException,
// as the JVM's bridges and Kotlin/Native give, except for the members of the collections that
// answer it (contains false, indexOf -1, get null, remove false).

class Holder<T>(var value: T) {
	fun put(item: T) {
		value = item
	}
}

class Ordered(val n: Int) : Comparable<Ordered> {
	override fun compareTo(other: Ordered) = n.compareTo(other.n)
}

@Suppress("UNCHECKED_CAST")
fun main() {
	val byLength = Comparator<String> { a, b -> a.length - b.length }
	val anyComparator = byLength as Comparator<Any>
	println(runCatching { anyComparator.compare(1, 2) }.exceptionOrNull()?.let { it::class.simpleName })
	println(anyComparator.compare("aa", "b"))

	val intCompare = Comparator<Int> { a, b -> a - b } as Comparator<Any?>
	println(runCatching { intCompare.compare("x", "y") }.exceptionOrNull()?.let { it::class.simpleName })

	val comparable = Ordered(1) as Comparable<Any>
	println(runCatching { comparable.compareTo("text") }.exceptionOrNull()?.let { it::class.simpleName })

	val ints = listOf(1, 2, 3) as List<Any?>
	println(ints.contains("2"))
	println(ints.indexOf("2"))
	println(ints.lastIndexOf(null))
	val map = mapOf(1 to "one") as Map<Any?, Any?>
	println(map["1"])
	println(map.containsKey("1"))
	println(map.containsValue(1))
	val mutable = mutableListOf(1, 2) as MutableList<Any?>
	println(mutable.remove("1"))
	println(mutable)

	val lambda: (Int) -> Int = { it * 2 }
	val anyLambda = lambda as (Any?) -> Any?
	println(runCatching { anyLambda("text") }.exceptionOrNull()?.let { it::class.simpleName })
	println(anyLambda(21))
}
