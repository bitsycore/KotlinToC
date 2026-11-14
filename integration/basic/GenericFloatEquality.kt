@file:OptIn(ExperimentalStdlibApi::class)

/** `==` between values of a type parameter that stands for Double or Float is `equals`, a value of type Nothing, and booleans compared. */
inline fun <K> sameBy(values: List<Double>, selector: (Double) -> K): Boolean {
	var first: K? = null
	for (i in values.indices) {
		val key = selector(values[i])
		if (i == 0) first = key else if (first != key) return false
	}
	return true
}

fun <T> same(a: T?, b: T): Boolean = a == b

fun main() {
	println(sameBy(listOf(Double.NaN, Double.NaN)) { it })
	println(sameBy(listOf(0.0, -0.0)) { it })
	println(sameBy(listOf(1.0, 1.0)) { it.toFloat() })
	println(same(Double.NaN, Double.NaN))
	println(same(0.0f, -0.0f))
	println(doubleArrayOf(1.0).allEqualBy { error("one element: not called") })
	val a: Double? = Double.NaN
	val b: Double = Double.NaN
	println(a == b)
	println(booleanArrayOf(false, true, true).isSorted())
	println(booleanArrayOf(true, false).isSorted())
	println(true.compareTo(false))
}
