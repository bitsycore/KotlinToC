// A property reference knows the name of its property (`KProperty.name`), reads it (`get`, or as
// a function) and, for a `var`, writes it (`set`), as on the JVM and Kotlin/Native.

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1

class Date(var year: Int, var month: Int) {
	val isLeap: Boolean get() = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}

var counter = 3

class Field<T, V>(val property: KMutableProperty1<T, V>, val label: String = property.name)

fun <T> describe(inProperty: KProperty1<T, *>, inValue: T): String = "${inProperty.name} = ${inProperty.get(inValue)}"

fun main() {
	val vDate = Date(2024, 2)
	val vYear = Field(Date::year)
	println(vYear.label)
	vYear.property.set(vDate, 2025)
	println(vYear.property.get(vDate))
	println(describe(Date::month, vDate))
	println(describe(Date::isLeap, vDate))
	val vTop: KProperty0<Int> = ::counter
	println("${vTop.name} ${vTop.get()} ${vTop()}")
	println(listOf(Date(2000, 1), Date(1900, 1)).map(Date::isLeap))
	val vBound = vDate::month
	println("${vBound.name} ${vBound.get()}")
	println(listOf(Date::year, Date::month).joinToString { it.name })
	println(describeBuilder())
}

class Builder {
	fun char(inValue: Char) {}
	fun optional(inBlock: () -> Unit) {}
}

fun describeBuilder(): String = "${Builder::char.name}('x') ${Builder::optional.name} { } ${::describeBuilder.name}"
