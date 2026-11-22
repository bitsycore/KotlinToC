@file:OptIn(ExperimentalNativeApi::class)

package kotlin.native

import kotlin.experimental.ExperimentalNativeApi
import ktc2.internal.throwIndexOutOfBounds

/*
ktc2 platform layer: Kotlin/Native's typed accessors of a ByteArray (kotlin-native/runtime/src/main/kotlin/kotlin/native/
TypedArrays.kt), which its runtime does in C++: a value of several bytes read or written at any index, little-endian,
as Native does on every target, and an ArrayIndexOutOfBoundsException when it does not fit in the array.
*/

private fun ByteArray.checkRoom(index: Int, bytes: Int) {
	if (index < 0 || index.toLong() + bytes > size) throwIndexOutOfBounds(index, size)
}

private fun ByteArray.bytesAt(index: Int, bytes: Int): Long {
	checkRoom(index, bytes)
	var vValue = 0L
	for (vAt in bytes - 1 downTo 0) vValue = (vValue shl 8) or (this[index + vAt].toLong() and 0xFF)
	return vValue
}

private fun ByteArray.putBytesAt(index: Int, bytes: Int, value: Long) {
	checkRoom(index, bytes)
	var vValue = value
	for (vAt in 0 until bytes) {
		this[index + vAt] = vValue.toByte()
		vValue = vValue ushr 8
	}
}

@ExperimentalNativeApi
public fun ByteArray.getUByteAt(index: Int): UByte = get(index).toUByte()

@ExperimentalNativeApi
public fun ByteArray.getCharAt(index: Int): Char = bytesAt(index, 2).toInt().toChar()

@ExperimentalNativeApi
public fun ByteArray.getShortAt(index: Int): Short = bytesAt(index, 2).toShort()

@ExperimentalNativeApi
@ExperimentalUnsignedTypes
public fun ByteArray.getUShortAt(index: Int): UShort = bytesAt(index, 2).toUShort()

@ExperimentalNativeApi
public fun ByteArray.getIntAt(index: Int): Int = bytesAt(index, 4).toInt()

@ExperimentalNativeApi
@ExperimentalUnsignedTypes
public fun ByteArray.getUIntAt(index: Int): UInt = bytesAt(index, 4).toUInt()

@ExperimentalNativeApi
public fun ByteArray.getLongAt(index: Int): Long = bytesAt(index, 8)

@ExperimentalNativeApi
@ExperimentalUnsignedTypes
public fun ByteArray.getULongAt(index: Int): ULong = bytesAt(index, 8).toULong()

@ExperimentalNativeApi
public fun ByteArray.getFloatAt(index: Int): Float = Float.fromBits(bytesAt(index, 4).toInt())

@ExperimentalNativeApi
public fun ByteArray.getDoubleAt(index: Int): Double = Double.fromBits(bytesAt(index, 8))

@ExperimentalNativeApi
public fun ByteArray.setUByteAt(index: Int, value: UByte) {
	set(index, value.toByte())
}

@ExperimentalNativeApi
public fun ByteArray.setCharAt(index: Int, value: Char): Unit = putBytesAt(index, 2, value.code.toLong())

@ExperimentalNativeApi
public fun ByteArray.setShortAt(index: Int, value: Short): Unit = putBytesAt(index, 2, value.toLong())

@ExperimentalNativeApi
@ExperimentalUnsignedTypes
public fun ByteArray.setUShortAt(index: Int, value: UShort): Unit = putBytesAt(index, 2, value.toLong())

@ExperimentalNativeApi
public fun ByteArray.setIntAt(index: Int, value: Int): Unit = putBytesAt(index, 4, value.toLong())

@ExperimentalNativeApi
public fun ByteArray.setUIntAt(index: Int, value: UInt): Unit = putBytesAt(index, 4, value.toLong())

@ExperimentalNativeApi
public fun ByteArray.setLongAt(index: Int, value: Long): Unit = putBytesAt(index, 8, value)

@ExperimentalNativeApi
@ExperimentalUnsignedTypes
public fun ByteArray.setULongAt(index: Int, value: ULong): Unit = putBytesAt(index, 8, value.toLong())

@ExperimentalNativeApi
public fun ByteArray.setFloatAt(index: Int, value: Float): Unit = putBytesAt(index, 4, value.toRawBits().toLong())

@ExperimentalNativeApi
public fun ByteArray.setDoubleAt(index: Int, value: Double): Unit = putBytesAt(index, 8, value.toRawBits())
