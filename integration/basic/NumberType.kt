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

fun main() {
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
