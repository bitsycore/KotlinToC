package ktc2test.language.unitvalues

import kotlin.test.Test

// ==================
// MARK: UnitValues
// ==================

// Unit as a value: a property of type Unit or Unit?, a Unit? that is null, printed and compared.

class Holder(val unit: Unit, var maybe: Unit?)

fun nothingToSay(): Unit? = null

@Test
fun unitValues() {
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

// ==================
// MARK: UnitLambdaParameter
// ==================

// A lambda given to an inline function whose parameter is the Unit a call returned:
// `validate(x).let { ... }`, as kotlinx-datetime writes it.

fun validate(inValue: Int) {
	if (inValue < 0) throw IllegalArgumentException("negative: $inValue")
}

fun describe(inValue: Int): String = validate(inValue).let { "valid $inValue" }

fun same(inValue: Int): Any = validate(inValue).let { it }

@Test
fun unitLambdaParameter() {
	println(describe(3))
	println(same(4))
	println(Unit.let { it.toString() })
	validate(5).also { println("also $it") }
	println(deep())
	try {
		describe(-1)
	} catch (vError: IllegalArgumentException) {
		println("caught ${vError.message}")
	}
}

val depth = DeepRecursiveFunction<Unit, Int> { 3 }

fun deep(): Int = depth(Unit)
