// Numbers that may be null where generic code tests and compares them, and the order
// `compareTo` gives the floating-point numbers.

fun <T> describe(inValue: T): String = when (inValue) {
	null -> "null"
	is CharSequence? -> "text"
	is Int -> "int"
	is Number -> "number"
	else -> "other"
}

/** Identity only against null: whether two boxes of one number are the same object is the JVM's own business. */
fun <T> same(inLeft: T, inRight: Any?): String = "${inLeft == inRight} ${inRight == null && inLeft === inRight} ${inRight == inLeft}"

/** A cast to a type parameter, which may stand for a type that takes null. */
@Suppress("UNCHECKED_CAST")
fun <T> pass(inValue: Any?): T = inValue as T

fun main() {
	val vInts = arrayListOf<Int?>(3, null, 1)
	println(vInts)
	println(vInts.joinToString("|"))
	println(vInts.map { describe(it) })
	val vLongs = listOf<Long?>(5L, null)
	println("$vLongs ${vLongs.map { describe(it) }}")
	val vOther: List<Any?> = listOf(3, null, 1)
	println("${vInts == vOther} ${vOther == vInts} ${vInts == listOf<Any?>(3, "x", 1)} ${vInts.contains(null)} ${vInts.indexOf(1)}")
	for (vValue in vInts) println(same(vValue, 3) + " " + same(vValue, null) + " " + same(vValue, "3"))

	val vValues = listOf(0.0, -0.0, 1.5, -1.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
	for (vLeft in vValues) {
		println(vValues.map { vLeft.compareTo(it) })
	}
	println(vValues.sorted())
	println(vValues.sortedDescending())
	println(listOf(2.5f, Float.NaN, -1.0f, -0.0f, 0.0f).sorted())
	println(vValues.map { it.toFloat() }.maxOrNull())

	// Numbers seen as a Comparable of their own kind.
	val vBoxed: List<Comparable<Double>> = vValues
	println(vBoxed.map { it.compareTo(0.0) })
	val vInt: Comparable<Int> = 5
	val vLong: Comparable<Long> = 5L
	val vFloat: Comparable<Float> = Float.NaN
	val vBoolean: Comparable<Boolean> = true
	val vText: Comparable<String> = "b"
	val vUnsigned: Comparable<UInt> = 3000000000u
	println("${vInt.compareTo(7)} ${vInt.compareTo(5)} ${vLong.compareTo(-1L)} ${vFloat.compareTo(1f)} ${vBoolean.compareTo(false)} ${vText.compareTo("a")} ${vUnsigned.compareTo(1u)}")
	println("${compareValues(Double.NaN, 1.0)} ${compareValues(-0.0, 0.0)} ${compareValues(2, 3)} ${compareValues<Int>(null, 3)} ${compareValues("b", "a")}")
	println(listOf(3.5, -1.0, Double.NaN).sortedWith(naturalOrder()))
	println(maxOf(vValues[2], vValues[3], compareBy { it }))

	// Numbers, and numbers that may be null, cast to a type of objects and tested there.
	println(vInts.map { (it as Any?) is Int })
	println(vInts.map { (it as Any?) is String? })
	println(vInts.map { (it as? Any) != null })
	println(vInts.map { (it as? Comparable<*>) != null })
	val vNumber = 7
	val vAsAny = vNumber as Any
	println("${vAsAny is Int} ${vAsAny is Long} $vAsAny ${(vNumber as? Any) == 7} ${(2.5 as Any) is Double}")
	println("${pass<String?>(null)} ${pass<Int?>(null)} ${pass<String>("s")} ${pass<Int>(4)}")
	try {
		println((null as String?) as String)
	} catch (inError: NullPointerException) {
		println("null is not a String")
	}
	try {
		println((vInts[1] as Any).toString())
	} catch (inError: NullPointerException) {
		println("null is not Any")
	}
}
