// `Comparator` and `Comparable` as ordinary code uses them: `compareBy` with several keys,
// `thenBy`, `thenByDescending`, `thenComparator`, `reversed`, nulls first and last, natural and
// reverse order, comparators of the program's own, `sortedWith` on lists, arrays and sequences,
// `maxWith`, `binarySearch` with a comparator, stability of sorting, `compareValues`,
// `Comparable` classes in sorted collections, and comparators made from lambdas and references.

package ktc2test.collections.ordinarycomparators

import kotlin.test.Test
import kotlin.math.sign

data class Person(val name: String, val age: Int, val city: String?, val score: Double)

class Version(val text: String) : Comparable<Version> {
	private val parts = text.split('.').map { it.toInt() }
	override fun compareTo(other: Version): Int {
		for (i in 0 until maxOf(parts.size, other.parts.size)) {
			val c = (parts.getOrNull(i) ?: 0).compareTo(other.parts.getOrNull(i) ?: 0)
			if (c != 0) return c
		}
		return 0
	}
	override fun toString() = text
}

object ByLength : Comparator<String> {
	override fun compare(a: String, b: String) = a.length - b.length
}

class CaseInsensitive : Comparator<String> {
	override fun compare(a: String, b: String) = a.lowercase().compareTo(b.lowercase())
}

fun <T> describe(items: List<T>, comparator: Comparator<in T>) = items.sortedWith(comparator).toString()

@Test
fun main() {
	val people = listOf(
		Person("Ann", 31, "Oslo", 7.5),
		Person("bob", 25, null, 9.0),
		Person("Cy", 31, "Bergen", 7.5),
		Person("dee", 25, "Oslo", 6.0),
		Person("Ed", 40, null, 9.0),
		Person("ann", 31, "Bergen", 8.0),
	)
	fun List<Person>.names() = map { it.name }

	println(people.sortedWith(compareBy({ it.age }, { it.name })).names())
	println(people.sortedWith(compareBy<Person> { it.age }.thenByDescending { it.score }.thenBy { it.name }).names())
	println(people.sortedWith(compareByDescending<Person> { it.score }.thenComparator { a, b -> a.name.length - b.name.length }).names())
	println(people.sortedWith(compareBy(nullsFirst()) { it.city }).names())
	println(people.sortedWith(compareBy(nullsLast()) { it.city }).names())
	println(people.sortedWith(compareBy<Person, String?>(nullsLast(reverseOrder())) { it.city }.thenBy { it.name }).names())
	println(people.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).names())
	println(people.sortedWith(compareBy<Person> { it.age }.reversed()).names())
	println(people.sortedWith(Comparator { a, b -> b.name.compareTo(a.name) }).names())
	println(people.sortedBy { it.name }.names())
	println(people.sortedByDescending { it.age }.names())
	println(people.maxWithOrNull(compareBy({ it.score }, { it.age }))?.name)
	println(people.minWith(compareBy { it.name.lowercase() }).name)
	println(people.maxByOrNull { it.score }?.name)
	println(people.groupBy { it.age }.toSortedMap2().keys)
	println(people.sortedWith(compareBy<Person> { it.city == null }.then(compareBy { it.name })).names())
	println(people.map { it.name }.sortedWith(naturalOrder()))
	println(people.map { it.name }.sortedWith(reverseOrder()))
	println(people.map { it.name }.sortedWith(ByLength.thenBy { it }))
	println(people.map { it.name }.sortedWith(CaseInsensitive().thenDescending(naturalOrder())))
	println(describe(listOf("pear", "fig", "apple"), ByLength))
	println(describe(listOf(3, 1, 2), Comparator<Any> { a, b -> a.hashCode() - b.hashCode() }))

	val versions = listOf("1.10", "1.2", "1.2.1", "1.9", "2", "1.2.0").map(::Version)
	println(versions.sorted())
	println(versions.max())
	println(versions.sortedDescending())
	println(Version("1.2") == Version("1.2.0"))
	println(Version("1.2").compareTo(Version("1.2.0")))

	val stable = listOf("b1", "a1", "b2", "a2", "b3", "a3").sortedBy { it[0] }
	println(stable)
	val array = arrayOf("delta", "Alpha", "charlie", "Bravo")
	array.sortWith(String.CASE_INSENSITIVE_ORDER)
	println(array.toList())
	array.sortWith(compareByDescending { it.length }, 1, 4)
	println(array.toList())
	val sortedNames = listOf("ann", "bob", "cy", "dee")
	println(sortedNames.binarySearch("cy"))
	println(sortedNames.binarySearch("bz"))
	println(listOf("a", "bb", "ccc").binarySearch("xx", ByLength))
	println(sortedNames.binarySearch { it.compareTo("dee") })
	println(sequenceOf(5, 3, 9, 1).sortedWith(reverseOrder()).toList())
	val mutable = mutableListOf(3, 1, 2)
	mutable.sortWith(compareBy { -it })
	println(mutable)
	mutable.sortDescending()
	println(mutable)
	println(compareValues(1, 2).toString() + compareValues(null, 1) + compareValues("b", "a") + compareValues<Int>(null, null))
	println(compareValuesBy(people[0], people[2], { it.age }, { it.name }).sign)
	println(maxOf("a", "B", String.CASE_INSENSITIVE_ORDER))
	println(minOf(3, 1, 2))
	println(listOf(1.5, -0.0, 0.0, Double.NaN, -1.0).sortedWith(naturalOrder()))
	println(listOf('b', 'A', 'a').sortedWith(compareBy { it.lowercaseChar() }))
	println(listOf(true, false, true).sorted())
	println(listOf(3L, -1L).sortedWith(comparingLong2 { it }))
	val byAge: Comparator<Person> = compareBy(Person::age)
	println(people.sortedWith(byAge.thenBy(Person::name)).names())
	println(listOf(Pair(2, "b"), Pair(1, "z"), Pair(2, "a")).sortedWith(compareBy({ it.first }, { it.second })))
	println(listOf("x" to 2, "y" to 1).sortedBy { it.second })
	println(listOf(listOf(2, 1), listOf(1, 3), listOf(1, 2)).sortedWith { a, b -> a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: (a.size - b.size) })
	println(people.sortedWith(compareBy { it.name }).reversed().names())
	println(people.asSequence().sortedWith(compareBy({ it.city ?: "" }, { -it.age })).map { it.name }.toList())
	println(listOf(Version("3"), Version("1")).sortedWith(nullsFirst()))
	println(listOf<String?>("b", null, "a").sortedWith(nullsLast()))
	println(listOf<String?>("b", null, "a").sortedWith(nullsFirst(reverseOrder())))
}

fun <K : Comparable<K>, V> Map<K, V>.toSortedMap2(): Map<K, V> = entries.sortedBy { it.key }.associate { it.key to it.value }

fun comparingLong2(key: (Long) -> Long): Comparator<Long> = Comparator { a, b -> key(a).compareTo(key(b)) }
