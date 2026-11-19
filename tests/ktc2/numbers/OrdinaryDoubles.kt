package ktc2test.numbers.ordinarydoubles

import kotlin.test.Test
import kotlin.math.nextDown
import kotlin.math.nextUp

// `Double` and `Float` as text, at their limits: the shortest digits that read back the same, the
// switch to an exponent, the smallest and largest values, subnormals, negative zero, NaN and the
// infinities, in templates, `toString`, `StringBuilder`, collections; and text read back.

@Test
fun main() {
	val doubles = listOf(
		0.0, -0.0, 1.0, -1.0, 0.1, 0.2, 0.1 + 0.2, 1.0 / 3, 2.0 / 3, 100.0, 1e6, 1e7 - 1, 1e7, 1.5e7,
		123456789.0, 1234567890123.0, 1e15, 1e16, 1e21, 1e22, 1e-3, 1e-4, 0.00123, 1.0E-5, 9.999999e-4,
		Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, 2.2250738585072014E-308, 4.9e-324 * 3,
		Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.7976931348623157e308 / 10,
		Math2.PI, Math2.E, 299792458.0, 6.02214076e23, 1.602176634e-19, 0.5, 0.25, 0.125, 1024.0 * 1024 * 1024,
		Long.MAX_VALUE.toDouble(), Int.MIN_VALUE.toDouble(), 9007199254740993.0, 0.30000000000000004, 1e-7, 123e-9,
	)
	for (d in doubles) println("$d ${d.toString().length} ${-d} ${d.toString().toDouble() == d || d.isNaN()}")

	val floats = listOf(
		0.0f, -0.0f, 1.0f, 0.1f, 0.2f, 0.1f + 0.2f, 1.0f / 3, 1e6f, 1e7f, 1.5e7f, 123456789f, 1e-3f, 1e-4f,
		Float.MAX_VALUE, Float.MIN_VALUE, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
		3.4028235e38f / 7, 16777217f, 0.3f, 2.5f, 100f / 3,
	)
	for (f in floats) println("$f ${f.toDouble()} ${f.toString().toFloat() == f || f.isNaN()}")

	println(StringBuilder().append(0.1).append(' ').append(1e-10).append(' ').append(1.0f / 3))
	println(listOf(1.0, 2.5, Double.NaN))
	println(mapOf(1.5 to -0.0))
	println(doubleArrayOf(1e100, -1e-100).contentToString())
	println(floatArrayOf(1e10f, 1e-10f).toList())
	println("${1.0 / 0} ${-1.0 / 0} ${0.0 / 0}")
	println(0.0 == -0.0)
	println((0.0).equals(-0.0))
	println(listOf(0.0, -0.0, Double.NaN, 1.0, Double.NEGATIVE_INFINITY).sorted())
	println(maxOf(0.0, -0.0))
	println(minOf(0.0, -0.0))
	println(Double.NaN == Double.NaN)
	println(setOf(Double.NaN, Double.NaN, 0.0, -0.0).size)

	for (text in listOf("1", "1.", ".5", "1e3", "1E-3", "-0", "+2.5", "  3.5  ", "0x1p3", "NaN", "Infinity", "-Infinity", "1_000", "1e", "abc", "1.7976931348623159e308", "4.9e-325", "1d", "1f", "2.5F")) {
		println("[$text] ${text.toDoubleOrNull()} ${text.toFloatOrNull()}")
	}

	println(1.23456789.toString().take(6))
	println((2.0).pow2(0.5))
	println(1e300 * 1e10)
	println(-1e-320 / 1e10)
	println(1.0.toBits().toString(16))
	println((-0.0).toBits().toString(16))
	println(Double.fromBits(0x3ff8000000000000))
	println(Float.fromBits(0x3fc00000))
	println(0.1f.toBits())
	println(Double.MIN_VALUE.toBits())
	println(1.0.ulp)
	println(1.0.nextUp())
	println(1.0.nextDown())
	println(Double.MAX_VALUE.nextUp())
	println(0.0.nextDown())
	println(1e16.toLong())
	println(1e19.toLong())
	println((-1e19).toLong())
	println(Double.NaN.toInt())
	println(3.99.toInt().toString() + " " + (-3.99).toInt())
	println(2.5.roundToInt2() + 3.5.roundToInt2() + (-2.5).roundToInt2())
	println(1e10.toFloat())
	println(16777217.0.toFloat())
	println(0.1.toFloat().toDouble())
	println(123.456.toString().split('.'))
	println("%.3f".length + "${0.1 * 3}".length)
	println(listOf(1.0, 1.10, 1.100).map { it.toString() })
	println(10.0.pow2(-5.0))
	println(1.0e-5 * 3)
	println(12345678.9)
	println(0.000001234)
	println(Double.MAX_VALUE.toString().length)
	println(Double.MIN_VALUE * 0.5)
	println(5e-324 / 2)
	println(-Double.MIN_VALUE)
	println(1.0E7.toFloat())
	println(9.999999E6f)
	println(1.0E-3f)
	println(0.001f.toDouble())
}

object Math2 {
	const val PI = 3.141592653589793
	const val E = 2.718281828459045
}

fun Double.pow2(x: Double): Double = kotlin.math.exp(x * kotlin.math.ln(this)).let { kotlin.math.round(it * 1e9) / 1e9 }

fun Double.roundToInt2(): Int = kotlin.math.round(this).toInt()

val Double.ulp get() = kotlin.math.abs(this.nextUp() - this)
