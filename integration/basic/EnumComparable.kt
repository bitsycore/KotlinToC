// An enum entry is a Comparable (kotlin.Enum is one): seen as Any, cast to it, compared by selectors.

enum class Month { JANUARY, FEBRUARY, MARCH }

class Period(val year: Int, val month: Month)

fun main() {
	val vAny: Any = Month.MARCH
	println(vAny is Comparable<*>)
	println(compareValues(Month.JANUARY, Month.MARCH))
	@Suppress("UNCHECKED_CAST")
	println((vAny as Comparable<Any>).compareTo(Month.FEBRUARY))
	val vPeriods = listOf(Period(2024, Month.MARCH), Period(2023, Month.FEBRUARY), Period(2024, Month.JANUARY))
	println(vPeriods.sortedWith(compareBy({ it.year }, { it.month })).joinToString { "${it.year}-${it.month}" })
	println(compareValuesBy(vPeriods[0], vPeriods[2], Period::year, Period::month))
}
