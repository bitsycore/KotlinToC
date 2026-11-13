@file:OptIn(ExperimentalUnsignedTypes::class, ExperimentalStdlibApi::class)

package kotlin.text

// ==================
// MARK: Hexadecimal text of unsigned numbers
// ==================

/*
The library writes these over the signed number an unsigned one holds (`data`), which only its
own module can reach. Here an unsigned number is a number of the machine: the same functions,
over the conversion to the signed kind, which changes no bit.
*/

public fun UByteArray.toHexString(format: HexFormat = HexFormat.Default): String = asByteArray().toHexString(format)

public fun UByteArray.toHexString(startIndex: Int = 0, endIndex: Int = size, format: HexFormat = HexFormat.Default): String =
	asByteArray().toHexString(startIndex, endIndex, format)

public fun String.hexToUByteArray(format: HexFormat = HexFormat.Default): UByteArray = hexToByteArray(format).asUByteArray()

public fun UByte.toHexString(format: HexFormat = HexFormat.Default): String = toByte().toHexString(format)

public fun String.hexToUByte(format: HexFormat = HexFormat.Default): UByte = hexToByte(format).toUByte()

public fun UShort.toHexString(format: HexFormat = HexFormat.Default): String = toShort().toHexString(format)

public fun String.hexToUShort(format: HexFormat = HexFormat.Default): UShort = hexToShort(format).toUShort()

public fun UInt.toHexString(format: HexFormat = HexFormat.Default): String = toInt().toHexString(format)

public fun String.hexToUInt(format: HexFormat = HexFormat.Default): UInt = hexToInt(format).toUInt()

public fun ULong.toHexString(format: HexFormat = HexFormat.Default): String = toLong().toHexString(format)

public fun String.hexToULong(format: HexFormat = HexFormat.Default): ULong = hexToLong(format).toULong()
