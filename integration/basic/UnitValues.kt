// Unit as a value: a property of type Unit or Unit?, a Unit? that is null, printed and compared.

class Holder(val unit: Unit, var maybe: Unit?)

fun nothingToSay(): Unit? = null

fun main() {
	val vHolder = Holder(Unit, null)
	println(vHolder.unit)
	println(vHolder.maybe)
	vHolder.maybe = Unit
	println("${vHolder.maybe} ${vHolder.unit == Unit}")
	println(nothingToSay())
	println(nothingToSay() ?: "none")
	val vList: List<Unit?> = listOf(Unit, null)
	println(vList)
	println(listOf(1, 2).map { }.size)
	println(WithUnit(Unit, null, 1) == WithUnit(unitFrom(Unit), null, 1))
	println(WithUnit(Unit, Unit, 1) == WithUnit(Unit, Unit, 1))
	println(WithUnit(Unit, Unit, 1).hashCode() == WithUnit(Unit, Unit, 1).hashCode())
	val vThings: List<Any?> = listOf(Unit, 1, null)
	println(vThings.map { it is Unit })
	println(vThings.map { it is Unit? })
	val vKept: Any? = keep(Unit).kept
	println("${vKept == null} ${vKept === Unit} ${keep(Unit).kept}")
	val vMade: Any? = makeWith(UnitMaker)
	println("${vMade == null} ${vMade === Unit}")
	try {
		unitFrom(1)
	} catch (vError: ClassCastException) {
		println("not Unit")
	}
}

data class WithUnit(val unit: Unit, val maybe: Unit?, val n: Int)

fun unitFrom(inAny: Any?): Unit = inAny as Unit

class Keeper<T>(val kept: T)

fun <T> keep(inValue: T): Keeper<T> = Keeper(inValue)

interface Maker<T> {
	fun make(): T
}

object UnitMaker : Maker<Unit> {
	override fun make() {}
}

fun <T> makeWith(inMaker: Maker<T>): T = inMaker.make()
