package basic

fun square(x: Int): Int = x * x

fun mix(a: Int, b: Long, c: Double): Double = a + b + c

fun Int.isEven(): Boolean = this % 2 == 0

fun main() {
	// Int: wrapping, division, remainder, shifts
	val vMax = 2147483647
	println(vMax + 1)
	println(-vMax - 2)
	println(vMax * 2)
	println(7 / 2)
	println(-7 / 2)
	println(7 % 3)
	println(-7 % 3)
	println((-2147483647 - 1) / -1)
	println((-2147483647 - 1) % -1)
	println(1 shl 31)
	println(1 shl 33)
	println(-16 shr 2)
	println(-16 ushr 28)
	println(0xF0 and 0x3C)
	println(0xF0 or 0x0F)
	println(0xF0 xor 0xFF)
	println(5.inv())
	println(-square(12))

	// Long
	val vBig = 9223372036854775807L
	println(vBig + 1)
	println(vBig / 3)
	println(1L shl 40)
	println(vMax.toLong() * 4)
	println(3000000000L.toInt())
	println(vBig.toInt())

	// Narrow types and Char
	val vByte: Byte = 127
	val vShort: Short = -300
	println(vByte + 1)
	println((vByte + 1).toByte())
	println(vShort * 2)
	println(300.toByte())
	val vChar = 'a'
	println(vChar)
	println(vChar + 2)
	println(vChar.code)
	println('z' - 'a')
	println(97.toChar())
	println('é')
	println(vChar < 'b')

	// Floating point: arithmetic, formatting, conversions
	println(1.0)
	println(0.1 + 0.2)
	println(1.0 / 3)
	println(100.0)
	println(1234567.0)
	println(12345678.0)
	println(0.001)
	println(0.0001)
	println(1.0e21)
	println(-2.5e-7)
	println(3.14f)
	println(1.0f / 3)
	println(16777216.0f)
	println(1.5e10f)
	println(7.5 % 2)
	println(0.0 / 0.0)
	println(1.0 / 0.0)
	println(-1.0 / 0.0)
	println(-0.0)
	println(3.99.toInt())
	println(-3.99.toInt())
	println(1.0e20.toInt())
	println((0.0 / 0.0).toInt())
	println(1.0e30.toLong())
	println(7.toDouble() / 2)
	println(mix(1, 2L, 0.5))
	println(2.5f.toDouble())
	println(1 / 2.0f)

	// Booleans and comparisons
	val vFlag = 3 > 2
	println(vFlag)
	println(!vFlag)
	println(vFlag && 1 > 2)
	println(vFlag || 1 > 2)
	println(vFlag xor true)
	println(3.compareTo(5))
	println(5L.compareTo(3))
	println(2.0 == 2.0)
	println(1 != 2)
	println(10.isEven())
	println(7.isEven())

	// Templates
	val vCount = 3
	println("count=$vCount double=${vCount * 2} ratio=${vCount / 2.0} ok=${vCount > 2} c=${'x'}")
	print("no newline, ")
	print(42)
	println()
	println("quote \" backslash \\ tab\t|")
}
