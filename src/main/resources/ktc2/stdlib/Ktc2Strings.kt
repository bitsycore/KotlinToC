@file:Suppress(
	"INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "NOTHING_TO_INLINE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT",
	"DEPRECATION", "DEPRECATION_ERROR", "UNUSED_PARAMETER", "ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS",
)

package kotlin.text

import ktc2.internal.stringOfChars
import ktc2.internal.stringOfUtf8
import ktc2.internal.stringSubstring
import ktc2.internal.stringToDouble
import ktc2.internal.stringUtf8

/*
ktc2 platform layer for kotlin.text: the String and Char functions the common standard
library leaves to its platform.

Classification and case of characters are Kotlin/Native's own code (commonNonJvmMain and
nativeWasmMain of the standard library, with their tables). Regex is in Ktc2Regex.kt, with the
tables of Ktc2Unicode.kt.
*/

// ==================
// MARK: Char
// ==================

public actual fun Char.isHighSurrogate(): Boolean = this >= '\uD800' && this <= '\uDBFF'

public actual fun Char.isLowSurrogate(): Boolean = this >= '\uDC00' && this <= '\uDFFF'

public actual fun Char.isISOControl(): Boolean = code <= 0x1F || (code >= 0x7F && code <= 0x9F)

// ==================
// MARK: String construction and conversion
// ==================

public actual fun String(chars: CharArray): String = stringOfChars(chars, 0, chars.size)

public actual fun String(chars: CharArray, offset: Int, length: Int): String {
	if (offset < 0 || length < 0 || offset > chars.size - length) throw IndexOutOfBoundsException("offset: $offset, length: $length, size: ${chars.size}")
	return stringOfChars(chars, offset, length)
}

public actual fun CharArray.concatToString(): String = stringOfChars(this, 0, size)

public actual fun CharArray.concatToString(startIndex: Int, endIndex: Int): String {
	AbstractList.checkBoundsIndexes(startIndex, endIndex, size)
	return stringOfChars(this, startIndex, endIndex - startIndex)
}

public actual fun String.toCharArray(): CharArray {
	val vChars = CharArray(length)
	ktc2.internal.stringToChars(this, vChars, 0)
	return vChars
}

public actual fun String.toCharArray(startIndex: Int, endIndex: Int): CharArray {
	AbstractList.checkBoundsIndexes(startIndex, endIndex, length)
	val vChars = CharArray(endIndex - startIndex)
	for (vIndex in startIndex until endIndex) vChars[vIndex - startIndex] = this[vIndex]
	return vChars
}

public actual fun String.toCharArray(destination: CharArray, destinationOffset: Int, startIndex: Int, endIndex: Int): CharArray {
	AbstractList.checkBoundsIndexes(startIndex, endIndex, length)
	AbstractList.checkBoundsIndexes(destinationOffset, destinationOffset + endIndex - startIndex, destination.size)
	for (vIndex in startIndex until endIndex) destination[destinationOffset + vIndex - startIndex] = this[vIndex]
	return destination
}

public actual open class CharacterCodingException(message: String?) : Exception(message) {
	public actual constructor() : this(null)
}

public actual fun ByteArray.decodeToString(): String = stringOfUtf8(this, 0, size)

public actual fun ByteArray.decodeToString(startIndex: Int, endIndex: Int, throwOnInvalidSequence: Boolean): String {
	AbstractList.checkBoundsIndexes(startIndex, endIndex, size)
	if (throwOnInvalidSequence && ktc2.internal.utf8Malformed(this, startIndex, endIndex)) throw CharacterCodingException("Malformed UTF-8 sequence")
	return stringOfUtf8(this, startIndex, endIndex)
}

public actual fun String.encodeToByteArray(): ByteArray = stringUtf8(this)

public actual fun String.encodeToByteArray(startIndex: Int, endIndex: Int, throwOnInvalidSequence: Boolean): ByteArray {
	AbstractList.checkBoundsIndexes(startIndex, endIndex, length)
	if (throwOnInvalidSequence) {
		var vIndex = startIndex
		while (vIndex < endIndex) {
			val vChar = this[vIndex++]
			if (!vChar.isSurrogate()) continue
			if (!vChar.isHighSurrogate() || vIndex == endIndex || !this[vIndex++].isLowSurrogate()) throw CharacterCodingException("Malformed sequence of UTF-16 characters")
		}
	}
	return stringUtf8(stringSubstring(this, startIndex, endIndex))
}

public actual fun String.substring(startIndex: Int): String = stringSubstring(this, startIndex, length)

public actual fun String.substring(startIndex: Int, endIndex: Int): String = stringSubstring(this, startIndex, endIndex)

public actual fun CharSequence.elementAt(index: Int): Char = this[index]

public actual fun CharSequence.repeat(n: Int): String {
	if (n < 0) throw IllegalArgumentException("Count 'n' must be non-negative, but was $n.")
	val vBuilder = StringBuilder(length * n)
	for (vTime in 0 until n) vBuilder.append(this)
	return vBuilder.toString()
}

// ==================
// MARK: Case
// ==================

// As Kotlin/Native: its own code, from nativeWasmMain (StringCasing.kt, with the tables of its generated files).
public actual fun String.uppercase(): String = uppercaseImpl()

public actual fun String.lowercase(): String = lowercaseImpl()

public actual fun String.toUpperCase(): String = uppercase()

public actual fun String.toLowerCase(): String = lowercase()

public actual fun String.capitalize(): String =
	if (length > 0 && this[0].isLowerCase()) this[0].uppercaseChar().toString() + substring(1) else this

public actual fun String.decapitalize(): String =
	if (length > 0 && this[0].isUpperCase()) this[0].lowercaseChar().toString() + substring(1) else this

// ==================
// MARK: Comparison and search
// ==================

private fun sameChar(a: Char, b: Char, ignoreCase: Boolean): Boolean {
	if (a == b) return true
	if (!ignoreCase) return false
	val vUpperA = a.uppercaseChar()
	val vUpperB = b.uppercaseChar()
	return vUpperA == vUpperB || vUpperA.lowercaseChar() == vUpperB.lowercaseChar()
}

public actual fun CharSequence.regionMatches(thisOffset: Int, other: CharSequence, otherOffset: Int, length: Int, ignoreCase: Boolean): Boolean {
	if (otherOffset < 0 || thisOffset < 0 || thisOffset > this.length - length || otherOffset > other.length - length) return false
	for (vIndex in 0 until length) {
		if (!sameChar(this[thisOffset + vIndex], other[otherOffset + vIndex], ignoreCase)) return false
	}
	return true
}

public actual fun String.regionMatches(thisOffset: Int, other: String, otherOffset: Int, length: Int, ignoreCase: Boolean): Boolean {
	if (otherOffset < 0 || thisOffset < 0 || thisOffset > this.length - length || otherOffset > other.length - length) return false
	if (length <= 0) return true
	if (!ignoreCase) {
		// Bytes compared at once when both strings are ASCII.
		val vSame = ktc2.internal.stringRegionEquals(this, thisOffset, other, otherOffset, length)
		if (vSame != -2) return vSame == 1
	}
	for (vIndex in 0 until length) {
		if (!sameChar(this[thisOffset + vIndex], other[otherOffset + vIndex], ignoreCase)) return false
	}
	return true
}

public actual fun String?.equals(other: String?, ignoreCase: Boolean): Boolean {
	if (this == null) return other == null
	if (other == null) return false
	if (!ignoreCase) return this == other
	return length == other.length && regionMatches(0, other, 0, length, true)
}

public actual fun String.compareTo(other: String, ignoreCase: Boolean): Int {
	if (!ignoreCase) return compareTo(other)
	val vLimit = if (length < other.length) length else other.length
	for (vIndex in 0 until vLimit) {
		val vMine = this[vIndex].uppercaseChar().lowercaseChar()
		val vTheirs = other[vIndex].uppercaseChar().lowercaseChar()
		// -1, 0 or 1, as on Kotlin/Native.
		if (vMine != vTheirs) return if (vMine < vTheirs) -1 else 1
	}
	return if (length == other.length) 0 else if (length < other.length) -1 else 1
}

private object CaseInsensitiveOrder : Comparator<String> {
	override fun compare(a: String, b: String): Int = a.compareTo(b, ignoreCase = true)
}

public actual val String.Companion.CASE_INSENSITIVE_ORDER: Comparator<String>
	get() = CaseInsensitiveOrder

public actual fun String.startsWith(prefix: String, ignoreCase: Boolean): Boolean = regionMatches(0, prefix, 0, prefix.length, ignoreCase)

public actual fun String.startsWith(prefix: String, startIndex: Int, ignoreCase: Boolean): Boolean =
	regionMatches(startIndex, prefix, 0, prefix.length, ignoreCase)

public actual fun String.endsWith(suffix: String, ignoreCase: Boolean): Boolean =
	regionMatches(length - suffix.length, suffix, 0, suffix.length, ignoreCase)

public actual infix fun CharSequence?.contentEquals(other: CharSequence?): Boolean = contentEquals(other, false)

public actual fun CharSequence?.contentEquals(other: CharSequence?, ignoreCase: Boolean): Boolean {
	if (this == null) return other == null
	if (other == null || length != other.length) return false
	for (vIndex in 0 until length) {
		if (!sameChar(this[vIndex], other[vIndex], ignoreCase)) return false
	}
	return true
}

internal actual fun String.nativeIndexOf(ch: Char, fromIndex: Int): Int {
	val vFound = ktc2.internal.stringFindChar(this, ch, fromIndex, false)
	if (vFound != -2) return vFound
	var vIndex = if (fromIndex < 0) 0 else fromIndex
	while (vIndex < length) {
		if (this[vIndex] == ch) return vIndex
		vIndex++
	}
	return -1
}

internal actual fun String.nativeLastIndexOf(ch: Char, fromIndex: Int): Int {
	val vFound = ktc2.internal.stringFindChar(this, ch, fromIndex, true)
	if (vFound != -2) return vFound
	var vIndex = if (fromIndex >= length) length - 1 else fromIndex
	while (vIndex >= 0) {
		if (this[vIndex] == ch) return vIndex
		vIndex--
	}
	return -1
}

internal actual fun String.nativeIndexOf(str: String, fromIndex: Int): Int {
	val vFound = ktc2.internal.stringFind(this, str, fromIndex, false)
	if (vFound != -2) return vFound
	var vIndex = if (fromIndex < 0) 0 else fromIndex
	// The empty string is found wherever the search starts, and at the end when it starts beyond it.
	if (str.isEmpty()) return if (vIndex <= length) vIndex else length
	while (vIndex + str.length <= length) {
		if (regionMatches(vIndex, str, 0, str.length, false)) return vIndex
		vIndex++
	}
	return -1
}

internal actual fun String.nativeLastIndexOf(str: String, fromIndex: Int): Int {
	val vFound = ktc2.internal.stringFind(this, str, fromIndex, true)
	if (vFound != -2) return vFound
	var vIndex = length - str.length
	if (vIndex > fromIndex) vIndex = fromIndex
	while (vIndex >= 0) {
		if (regionMatches(vIndex, str, 0, str.length, false)) return vIndex
		vIndex--
	}
	return -1
}

// ==================
// MARK: Replace
// ==================

public actual fun String.replace(oldChar: Char, newChar: Char, ignoreCase: Boolean): String {
	val vBuilder = StringBuilder(length)
	for (vIndex in 0 until length) {
		val vChar = this[vIndex]
		vBuilder.append(if (sameChar(vChar, oldChar, ignoreCase)) newChar else vChar)
	}
	return vBuilder.toString()
}

/** Replaces up to [limit] occurrences of [oldValue] (all of them when [limit] is negative). */
private fun replaceOccurrences(source: String, oldValue: String, newValue: String, ignoreCase: Boolean, limit: Int): String {
	val vBuilder = StringBuilder(source.length)
	var vIndex = 0
	var vReplaced = 0
	while (vIndex <= source.length) {
		val vMatches = (limit < 0 || vReplaced < limit) && source.regionMatches(vIndex, oldValue, 0, oldValue.length, ignoreCase)
		if (vMatches) {
			vBuilder.append(newValue)
			vReplaced++
			if (oldValue.length == 0) {
				// An empty pattern matches between every two characters.
				if (vIndex < source.length) vBuilder.append(source[vIndex])
				vIndex++
			} else {
				vIndex += oldValue.length
			}
		} else {
			if (vIndex < source.length) vBuilder.append(source[vIndex])
			vIndex++
		}
	}
	return vBuilder.toString()
}

public actual fun String.replace(oldValue: String, newValue: String, ignoreCase: Boolean): String =
	replaceOccurrences(this, oldValue, newValue, ignoreCase, -1)

public actual fun String.replaceFirst(oldChar: Char, newChar: Char, ignoreCase: Boolean): String {
	val vBuilder = StringBuilder(length)
	var vDone = false
	for (vIndex in 0 until length) {
		val vChar = this[vIndex]
		if (!vDone && sameChar(vChar, oldChar, ignoreCase)) {
			vBuilder.append(newChar)
			vDone = true
		} else {
			vBuilder.append(vChar)
		}
	}
	return vBuilder.toString()
}

public actual fun String.replaceFirst(oldValue: String, newValue: String, ignoreCase: Boolean): String =
	replaceOccurrences(this, oldValue, newValue, ignoreCase, 1)

// ==================
// MARK: Numbers
// ==================

// checkRadix is Kotlin/Native's (nativeWasmMain/kotlin/text/Char.kt).

internal actual fun digitOf(char: Char, radix: Int): Int {
	val vCode = char.code
	val vDigit = when {
		vCode >= '0'.code && vCode <= '9'.code -> vCode - '0'.code
		vCode >= 'a'.code && vCode <= 'z'.code -> vCode - 'a'.code + 10
		vCode >= 'A'.code && vCode <= 'Z'.code -> vCode - 'A'.code + 10
		vCode < 0x80 -> -1
		// The letters of full width count as the plain ones do; the digits of every script as digits, by
		// Kotlin/Native's table (nativeWasmMain/generated/_DigitChars.kt).
		vCode >= 0xFF21 && vCode <= 0xFF3A -> vCode - 0xFF21 + 10
		vCode >= 0xFF41 && vCode <= 0xFF5A -> vCode - 0xFF41 + 10
		else -> digitToIntImpl(vCode)
	}
	return if (vDigit >= radix) -1 else vDigit
}

/** A text that is no whole number: as on Kotlin/Native, the exception has no message. */
private fun numberFormatError(): Nothing = throw NumberFormatException()

/** Parses a whole number in [min, max]; fails as Kotlin/Native does. */
private fun parseWhole(input: String, radix: Int, min: Long, max: Long): Long {
	checkRadix(radix)
	if (input.length == 0) numberFormatError()
	var vIndex = 0
	var vNegative = false
	if (input[0] == '-' || input[0] == '+') {
		vNegative = input[0] == '-'
		vIndex = 1
		if (input.length == 1) numberFormatError()
	}
	// Accumulate negatively: the magnitude of the minimum is one more than the maximum.
	val vLimit = if (vNegative) min else -max
	var vResult = 0L
	while (vIndex < input.length) {
		val vDigit = digitOf(input[vIndex], radix)
		if (vDigit < 0 || vResult < (vLimit + vDigit) / radix) numberFormatError()
		vResult = vResult * radix - vDigit
		vIndex++
	}
	return if (vNegative) vResult else -vResult
}

public actual fun String.toByte(): Byte = parseWhole(this, 10, -128L, 127L).toByte()

public actual fun String.toByte(radix: Int): Byte = parseWhole(this, radix, -128L, 127L).toByte()

public actual fun String.toShort(): Short = parseWhole(this, 10, -32768L, 32767L).toShort()

public actual fun String.toShort(radix: Int): Short = parseWhole(this, radix, -32768L, 32767L).toShort()

public actual fun String.toInt(): Int = parseWhole(this, 10, -2147483648L, 2147483647L).toInt()

public actual fun String.toInt(radix: Int): Int = parseWhole(this, radix, -2147483648L, 2147483647L).toInt()

public actual fun String.toLong(): Long = parseWhole(this, 10, Long.MIN_VALUE, Long.MAX_VALUE)

public actual fun String.toLong(radix: Int): Long = parseWhole(this, radix, Long.MIN_VALUE, Long.MAX_VALUE)

public actual fun String.toDoubleOrNull(): Double? {
	val vResult = DoubleArray(1)
	return if (stringToDouble(this, vResult)) vResult[0] else null
}

// As on Kotlin/Native, the message is the text without the blanks around it.
public actual fun String.toDouble(): Double = toDoubleOrNull() ?: throw NumberFormatException(trim { it <= ' ' })

public actual fun String.toFloatOrNull(): Float? = toDoubleOrNull()?.toFloat()

public actual fun String.toFloat(): Float = toDouble().toFloat()

public actual fun String?.toBoolean(): Boolean = this != null && this.lowercase() == "true"

/** [value] in [radix], as `Long.toString(radix)`. */
private fun wholeToString(value: Long, radix: Int): String {
	checkRadix(radix)
	if (value == 0L) return "0"
	val vDigits = CharArray(65)
	var vPosition = 65
	// Work on the negative magnitude so that Long.MIN_VALUE needs no special case.
	var vRest = if (value < 0) value else -value
	while (vRest != 0L) {
		val vDigit = -(vRest % radix).toInt()
		vPosition--
		vDigits[vPosition] = if (vDigit < 10) ('0'.code + vDigit).toChar() else ('a'.code + vDigit - 10).toChar()
		vRest /= radix
	}
	if (value < 0) {
		vPosition--
		vDigits[vPosition] = '-'
	}
	return stringOfChars(vDigits, vPosition, 65 - vPosition)
}

public actual fun Byte.toString(radix: Int): String = wholeToString(this.toLong(), radix)

public actual fun Short.toString(radix: Int): String = wholeToString(this.toLong(), radix)

public actual fun Int.toString(radix: Int): String = wholeToString(this.toLong(), radix)

public actual fun Long.toString(radix: Int): String = wholeToString(this, radix)
