// `Number` as a type of its own: any of the numbers, converted by what it is.

fun describe(inValue: Any?): String = when (inValue) {
	is Number -> "number ${inValue.toInt()} ${inValue.toLong()} ${inValue.toDouble()} ${inValue.toShort()} ${inValue.toByte()} ${inValue.toFloat()}"
	null -> "null"
	else -> "no number: $inValue"
}

fun total(inValues: List<Number>): Double {
	var vSum = 0.0
	for (vValue in inValues) vSum += vValue.toDouble()
	return vSum
}

fun <T : Number> half(inValue: T): Double = inValue.toDouble() / 2

/** A number of the program's own. */
class Money(val cents: Long) : Number(), Comparable<Money> {
	override fun toByte(): Byte = cents.toByte()
	override fun toShort(): Short = cents.toShort()
	override fun toInt(): Int = cents.toInt()
	override fun toLong(): Long = cents
	override fun toFloat(): Float = cents / 100f
	override fun toDouble(): Double = cents / 100.0
	override fun compareTo(other: Money): Int = cents.compareTo(other.cents)
	override fun toString(): String = "$cents c"
	override fun equals(other: Any?): Boolean = other is Money && other.cents == cents
	override fun hashCode(): Int = cents.toInt()
}

/** Another, which leaves `toString` and `hashCode` as they are. */
open class Ratio(val over: Int, val under: Int) : Number() {
	override fun toByte(): Byte = toInt().toByte()
	override fun toShort(): Short = toInt().toShort()
	override fun toInt(): Int = over / under
	override fun toLong(): Long = toInt().toLong()
	override fun toFloat(): Float = over.toFloat() / under
	override fun toDouble(): Double = over.toDouble() / under
}

class Half : Ratio(1, 2) {
	override fun toInt(): Int = 1
}

fun ownNumbers() {
	val vMoney = Money(1250)
	val vAll: List<Number> = listOf(1, vMoney, 2.5, Ratio(7, 2), Half(), 9L)
	for (vValue in vAll) println(describe(vValue))
	println(total(vAll))
	println(vAll.map { it is Money })
	println(vAll.filterIsInstance<Ratio>().map { it.toDouble() })
	println("${half(vMoney)} ${half(Ratio(3, 4))} ${vMoney.toDouble()} ${Half().toLong()}")
	val vAny: Any = vMoney
	println("${vAny is Number} ${vAny is Comparable<*>} ${(vAny as Number).toLong()} ${(vAny as? Number)?.toShort()} ${vAny == Money(1250)} ${vAny.hashCode()} $vAny")
	val vNumber: Number = vMoney
	println("${vNumber is Money} ${vNumber is Int} ${vNumber == 1250} ${vNumber.hashCode()} $vNumber ${vNumber.toByte()}")
	println("${("text" as Any) is Number} ${(Ratio(1, 1) as Any) is Money} ${listOf<Any?>(null, vMoney, 3, "x").map { it is Number }}")
	println(listOf(Money(3), Money(1), Money(2)).sorted())
	val vByName = HashMap<String, Number>()
	vByName["money"] = vMoney
	vByName["int"] = 4
	println(vByName["money"]!!.toInt() + vByName["int"]!!.toInt())
}

fun main() {
	ownNumbers()
	val vValues: List<Any?> = listOf(
		7, -7, 300, Int.MAX_VALUE, Int.MIN_VALUE, 5L, Long.MAX_VALUE, Long.MIN_VALUE, 4000000000L,
		1.5, -1.5, 1e20, -1e20, Double.NaN, Double.POSITIVE_INFINITY, 2.75f, 3.0e10f,
		12.toByte(), (-3).toShort(), 'c', true, "text", 3u, null,
	)
	for (vValue in vValues) println(describe(vValue))

	val vNumbers = listOf<Number>(1, 2L, 3.5, 4.25f, 5.toShort(), 6.toByte())
	println(total(vNumbers))
	println(vNumbers.map { it.toInt() })
	println(vNumbers.map { it is Int })
	println(vNumbers)
	println("${half(9)} ${half(2.5)} ${half(7L)}")

	val vInt = 40
	val vAsNumber = vInt as Number
	val vOptional: Number? = if (vInt > 0) 2.5 else null
	println("${vAsNumber.toDouble()} ${vAsNumber.toLong() + 2} ${vAsNumber == 40} ${vAsNumber.hashCode()} $vAsNumber")
	println("${vOptional?.toInt()} ${vOptional is Number} ${vOptional is Double} ${(null as Number?) is Number} ${(null as Number?) is Number?}")
	val vAny: Any = "x"
	println("${vAny as? Number} ${(7 as Any) as? Number} ${(vAny as? Number)?.toInt() ?: -1}")
	try {
		println((vAny as Number).toInt())
	} catch (inError: ClassCastException) {
		println("a String is no Number")
	}
	val vByKind = HashMap<String, Number>()
	vByKind["int"] = 1
	vByKind["double"] = 2.5
	println("${vByKind["int"]!!.toDouble() + vByKind["double"]!!.toDouble()} ${vByKind["none"]?.toInt()}")
}
