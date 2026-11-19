// Value classes (`@JvmInline value class`) as ordinary code writes them: wrappers of numbers and
// strings with members and operators, in collections, nullable, as generic arguments, behind an
// interface, as keys, compared, with `init` checks, secondary constructors and companions.

package ktc2test.language.ordinaryvalueclasses

import kotlin.test.Test

@JvmInline
value class Meters(val value: Double) : Comparable<Meters> {
	operator fun plus(other: Meters) = Meters(value + other.value)
	operator fun times(by: Int) = Meters(value * by)
	override fun compareTo(other: Meters) = value.compareTo(other.value)
	fun toCentimeters() = value * 100
	override fun toString() = "${value}m"
}

@JvmInline
value class UserId(val raw: Long) {
	init {
		require(raw > 0) { "id must be positive: $raw" }
	}

	fun next() = UserId(raw + 1)

	companion object {
		val FIRST = UserId(1)
		fun parse(text: String): UserId? = text.toLongOrNull()?.takeIf { it > 0 }?.let { UserId(it) }
	}
}

@JvmInline
value class Name(val text: String) {
	constructor(first: String, last: String) : this("$first $last")

	val initials get() = text.split(" ").joinToString("") { it.take(1) }
	val length get() = text.length
}

interface Shape {
	fun area(): Double
}

@JvmInline
value class Square(val side: Int) : Shape {
	override fun area() = (side * side).toDouble()
}

@JvmInline
value class Wrapper<T>(val item: T) {
	fun <R> map(f: (T) -> R) = Wrapper(f(item))
}

@JvmInline
value class Flags(val bits: Int) {
	operator fun contains(flag: Int) = bits and flag != 0
	infix fun with(flag: Int) = Flags(bits or flag)
}

data class Person(val id: UserId, val name: Name, val height: Meters?)

fun total(distances: List<Meters>) = distances.fold(Meters(0.0)) { acc, m -> acc + m }

fun describe(shape: Shape) = "area ${shape.area()}"

fun <T> firstOf(items: List<T>): T = items.first()

fun lookup(id: UserId?): String = id?.let { "user ${it.raw}" } ?: "nobody"

fun anyValue(value: Any?): String = when (value) {
	is Meters -> "meters ${value.value}"
	is UserId -> "user id ${value.raw}"
	is Name -> "name ${value.text}"
	null -> "null"
	else -> "other $value"
}

@Test
fun main() {
	val a = Meters(1.5)
	val b = Meters(2.25)
	println(a + b)
	println((a * 4).toCentimeters())
	println(a < b)
	println(listOf(b, a, Meters(0.5)).sorted())
	println(listOf(b, a).maxOrNull())
	println(total(listOf(a, b, Meters(1.0))))
	println(a == Meters(1.5))
	println(a.hashCode() == Meters(1.5).hashCode())

	println(UserId.FIRST.next().next())
	println(UserId.parse("42"))
	println(UserId.parse("-1"))
	println(runCatching { UserId(0) }.exceptionOrNull()?.message)
	println(lookup(UserId(7)))
	println(lookup(null))

	val name = Name("Ada", "Lovelace")
	println(name)
	println(name.initials + " " + name.length)

	println(describe(Square(3)))
	val shapes: List<Shape> = listOf(Square(1), Square(2))
	println(shapes.sumOf { it.area() })
	println(shapes[1] is Square)
	println((shapes[0] as Square).side)

	val wrapped = Wrapper(21).map { it * 2 }.map { "<$it>" }
	println(wrapped)
	println(wrapped.item.length)

	val flags = Flags(0) with 1 with 4
	println("${1 in flags} ${2 in flags} ${4 in flags} $flags")

	val people = listOf(
		Person(UserId(3), Name("Bo"), Meters(1.8)),
		Person(UserId(1), Name("Al"), null),
		Person(UserId(2), Name("Cy"), Meters(1.6)),
	)
	println(people.sortedBy { it.id.raw })
	println(people.mapNotNull { it.height }.sorted())
	println(people.associateBy { it.id }[UserId(2)]?.name)
	println(people.groupBy { it.height != null }.mapValues { (_, v) -> v.map { it.name.text } })
	println(people[0] == Person(UserId(3), Name("Bo"), Meters(1.8)))
	println(people[0].copy(height = Meters(2.0)))

	val byId = HashMap<UserId, String>()
	byId[UserId(10)] = "ten"
	byId[UserId(10)] = "TEN"
	byId[UserId(11)] = "eleven"
	println(byId.size.toString() + " " + byId[UserId(10)])
	println(setOf(Meters(1.0), Meters(1.0), Meters(2.0)).size)

	println(firstOf(listOf(Name("x"), Name("y"))))
	val values: List<Any?> = listOf(Meters(3.0), UserId(5), Name("z"), null, 7)
	println(values.map { anyValue(it) })
	val nullable: Meters? = if (values.size > 2) Meters(9.0) else null
	println(nullable?.toCentimeters())
	val array = arrayOf(Meters(1.0), Meters(2.0))
	println(array.toList())
	val lambda: (Meters) -> Meters = { it * 3 }
	println(lambda(Meters(1.0)))
	println(listOf(1, 2, 3).map(::Square).map(Square::area))
}
