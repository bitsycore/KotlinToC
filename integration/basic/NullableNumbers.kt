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
}
