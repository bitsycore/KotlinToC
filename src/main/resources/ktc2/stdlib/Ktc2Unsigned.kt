@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_WITHOUT_EXPECT", "NOTHING_TO_INLINE")

package kotlin

/*
ktc2 platform layer for the unsigned numbers: what the common library leaves to its platform.
An unsigned number is a C unsigned integer here, so most of these are the operator itself.
*/

internal actual fun uintRemainder(v1: UInt, v2: UInt): UInt = v1 % v2

internal actual fun ulongRemainder(v1: ULong, v2: ULong): ULong = v1 % v2

internal actual fun uintDivide(v1: UInt, v2: UInt): UInt = v1 / v2

internal actual fun ulongDivide(v1: ULong, v2: ULong): ULong = v1 / v2

internal actual fun uintCompare(v1: Int, v2: Int): Int = (v1 xor Int.MIN_VALUE).compareTo(v2 xor Int.MIN_VALUE)

internal actual fun ulongCompare(v1: Long, v2: Long): Int = (v1 xor Long.MIN_VALUE).compareTo(v2 xor Long.MIN_VALUE)

internal actual fun uintToULong(value: Int): ULong = value.toUInt().toULong()

internal actual fun uintToLong(value: Int): Long = value.toLong() and 0xFFFF_FFFFL

internal actual fun uintToFloat(value: Int): Float = uintToDouble(value).toFloat()

internal actual fun floatToUInt(value: Float): UInt = doubleToUInt(value.toDouble())

internal actual fun uintToDouble(value: Int): Double = (value and Int.MAX_VALUE).toDouble() + (value ushr 31 shl 30).toDouble() * 2

internal actual fun doubleToUInt(value: Double): UInt = when {
	value.isNaN() -> 0u
	value <= UInt.MIN_VALUE.toDouble() -> UInt.MIN_VALUE
	value >= UInt.MAX_VALUE.toDouble() -> UInt.MAX_VALUE
	value <= Int.MAX_VALUE -> value.toInt().toUInt()
	else -> (value - Int.MAX_VALUE).toInt().toUInt() + Int.MAX_VALUE.toUInt()
}

internal actual fun ulongToFloat(value: Long): Float = ulongToDouble(value).toFloat()

internal actual fun floatToULong(value: Float): ULong = doubleToULong(value.toDouble())

internal actual fun ulongToDouble(value: Long): Double = (value ushr 11).toDouble() * 2048 + (value and 2047)

internal actual fun doubleToULong(value: Double): ULong = when {
	value.isNaN() -> 0u
	value <= ULong.MIN_VALUE.toDouble() -> ULong.MIN_VALUE
	value >= ULong.MAX_VALUE.toDouble() -> ULong.MAX_VALUE
	value < Long.MAX_VALUE -> value.toLong().toULong()
	// The top bit is set: the rest is what is left above 2^63.
	else -> (value - 9223372036854775808.0).toLong().toULong() + 9223372036854775808uL
}

internal actual fun uintToString(value: Int): String = uintToLong(value).toString()

internal actual fun uintToString(value: Int, base: Int): String = ulongToString(uintToLong(value), base)

internal actual fun ulongToString(value: Long): String = ulongToString(value, 10)

internal actual fun ulongToString(value: Long, base: Int): String {
	if (value >= 0) return value.toString(base)
	// The number is 2^64 + value: divided by the base in two steps that both fit.
	var vQuotient = ((value ushr 1) / base) shl 1
	var vRest = value - vQuotient * base
	if (vRest >= base) {
		vRest -= base
		vQuotient += 1
	}
	return vQuotient.toString(base) + vRest.toString(base)
}
