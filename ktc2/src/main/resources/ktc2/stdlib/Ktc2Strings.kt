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

Limits, to be lifted: case conversion and the Char classification functions know ASCII and
Latin-1 and treat the rest of Unicode approximately (any other letter-range code point is a
letter, without case); Regex is declared but not implemented.
*/

// ==================
// MARK: Char
// ==================

public actual fun Char.isHighSurrogate(): Boolean = this >= '\uD800' && this <= '\uDBFF'

public actual fun Char.isLowSurrogate(): Boolean = this >= '\uDC00' && this <= '\uDFFF'

public actual fun Char.lowercaseChar(): Char {
	val vCode = code
	if (vCode >= 'A'.code && vCode <= 'Z'.code) return (vCode + 32).toChar()
	if (vCode >= 0xC0 && vCode <= 0xDE && vCode != 0xD7) return (vCode + 32).toChar()
	return this
}

public actual fun Char.uppercaseChar(): Char {
	val vCode = code
	if (vCode >= 'a'.code && vCode <= 'z'.code) return (vCode - 32).toChar()
	if (vCode >= 0xE0 && vCode <= 0xFE && vCode != 0xF7) return (vCode - 32).toChar()
	return this
}

public actual fun Char.toLowerCase(): Char = lowercaseChar()

public actual fun Char.toUpperCase(): Char = uppercaseChar()

public actual fun Char.lowercase(): String = lowercaseChar().toString()

public actual fun Char.uppercase(): String = uppercaseChar().toString()

public actual fun Char.titlecaseChar(): Char = uppercaseChar()

public actual fun Char.isDigit(): Boolean = this >= '0' && this <= '9'

public actual fun Char.isUpperCase(): Boolean {
	val vCode = code
	return (vCode >= 'A'.code && vCode <= 'Z'.code) || (vCode >= 0xC0 && vCode <= 0xDE && vCode != 0xD7)
}

public actual fun Char.isLowerCase(): Boolean {
	val vCode = code
	return (vCode >= 'a'.code && vCode <= 'z'.code) || (vCode >= 0xDF && vCode <= 0xFF && vCode != 0xF7)
}

public actual fun Char.isTitleCase(): Boolean = false

public actual fun Char.isLetter(): Boolean {
	val vCode = code
	if (vCode < 0x80) return (vCode >= 'A'.code && vCode <= 'Z'.code) || (vCode >= 'a'.code && vCode <= 'z'.code)
	if (vCode < 0xC0) return vCode == 0xAA || vCode == 0xB5 || vCode == 0xBA
	if (vCode < 0x100) return vCode != 0xD7 && vCode != 0xF7
	// Beyond Latin-1: everything outside the punctuation, symbol and surrogate blocks.
	return !(vCode >= 0x2000 && vCode <= 0x2BFF) && !(vCode >= 0x3000 && vCode <= 0x303F) && !(vCode >= 0xD800 && vCode <= 0xF8FF) && vCode < 0xFE00
}

public actual fun Char.isLetterOrDigit(): Boolean = isLetter() || isDigit()

public actual fun Char.isDefined(): Boolean = !(this >= '\uD800' && this <= '\uDFFF')

public actual fun Char.isISOControl(): Boolean = code <= 0x1F || (code >= 0x7F && code <= 0x9F)

public actual fun Char.isWhitespace(): Boolean {
	val vCode = code
	return (vCode >= 0x09 && vCode <= 0x0D) || (vCode >= 0x1C && vCode <= 0x20) || vCode == 0xA0 || vCode == 0x1680 ||
		(vCode >= 0x2000 && vCode <= 0x200A) || vCode == 0x2028 || vCode == 0x2029 || vCode == 0x202F || vCode == 0x205F || vCode == 0x3000
}

public actual val Char.category: CharCategory
	get() = when {
		isUpperCase() -> CharCategory.UPPERCASE_LETTER
		isLowerCase() -> CharCategory.LOWERCASE_LETTER
		isLetter() -> CharCategory.OTHER_LETTER
		isDigit() -> CharCategory.DECIMAL_DIGIT_NUMBER
		this == ' ' -> CharCategory.SPACE_SEPARATOR
		isISOControl() -> CharCategory.CONTROL
		isHighSurrogate() || isLowSurrogate() -> CharCategory.SURROGATE
		else -> CharCategory.OTHER_SYMBOL
	}

public actual enum class CharCategory(public actual val code: String) {
	UNASSIGNED("Cn"),
	UPPERCASE_LETTER("Lu"),
	LOWERCASE_LETTER("Ll"),
	TITLECASE_LETTER("Lt"),
	MODIFIER_LETTER("Lm"),
	OTHER_LETTER("Lo"),
	NON_SPACING_MARK("Mn"),
	ENCLOSING_MARK("Me"),
	COMBINING_SPACING_MARK("Mc"),
	DECIMAL_DIGIT_NUMBER("Nd"),
	LETTER_NUMBER("Nl"),
	OTHER_NUMBER("No"),
	SPACE_SEPARATOR("Zs"),
	LINE_SEPARATOR("Zl"),
	PARAGRAPH_SEPARATOR("Zp"),
	CONTROL("Cc"),
	FORMAT("Cf"),
	PRIVATE_USE("Co"),
	SURROGATE("Cs"),
	DASH_PUNCTUATION("Pd"),
	START_PUNCTUATION("Ps"),
	END_PUNCTUATION("Pe"),
	CONNECTOR_PUNCTUATION("Pc"),
	OTHER_PUNCTUATION("Po"),
	MATH_SYMBOL("Sm"),
	CURRENCY_SYMBOL("Sc"),
	MODIFIER_SYMBOL("Sk"),
	OTHER_SYMBOL("So"),
	INITIAL_QUOTE_PUNCTUATION("Pi"),
	FINAL_QUOTE_PUNCTUATION("Pf");

	public actual operator fun contains(char: Char): Boolean = char.category == this
}

// ==================
// MARK: String construction and conversion
// ==================

public actual fun String(chars: CharArray): String = stringOfChars(chars, 0, chars.size)

public actual fun String(chars: CharArray, offset: Int, length: Int): String = stringOfChars(chars, offset, length)

public actual fun CharArray.concatToString(): String = stringOfChars(this, 0, size)

public actual fun CharArray.concatToString(startIndex: Int, endIndex: Int): String = stringOfChars(this, startIndex, endIndex - startIndex)

public actual fun String.toCharArray(): CharArray = toCharArray(0, length)

public actual fun String.toCharArray(startIndex: Int, endIndex: Int): CharArray {
	val vChars = CharArray(endIndex - startIndex)
	for (vIndex in startIndex until endIndex) vChars[vIndex - startIndex] = this[vIndex]
	return vChars
}

public actual fun String.toCharArray(destination: CharArray, destinationOffset: Int, startIndex: Int, endIndex: Int): CharArray {
	for (vIndex in startIndex until endIndex) destination[destinationOffset + vIndex - startIndex] = this[vIndex]
	return destination
}

public actual fun ByteArray.decodeToString(): String = stringOfUtf8(this, 0, size)

public actual fun ByteArray.decodeToString(startIndex: Int, endIndex: Int, throwOnInvalidSequence: Boolean): String =
	stringOfUtf8(this, startIndex, endIndex)

public actual fun String.encodeToByteArray(): ByteArray = stringUtf8(this)

public actual fun String.encodeToByteArray(startIndex: Int, endIndex: Int, throwOnInvalidSequence: Boolean): ByteArray =
	stringUtf8(stringSubstring(this, startIndex, endIndex))

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

public actual fun String.uppercase(): String {
	val vBuilder = StringBuilder(length)
	for (vIndex in 0 until length) vBuilder.append(this[vIndex].uppercaseChar())
	return vBuilder.toString()
}

public actual fun String.lowercase(): String {
	val vBuilder = StringBuilder(length)
	for (vIndex in 0 until length) vBuilder.append(this[vIndex].lowercaseChar())
	return vBuilder.toString()
}

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
		if (vMine != vTheirs) return vMine.code - vTheirs.code
	}
	return length - other.length
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
	var vIndex = if (fromIndex < 0) 0 else fromIndex
	while (vIndex < length) {
		if (this[vIndex] == ch) return vIndex
		vIndex++
	}
	return -1
}

internal actual fun String.nativeLastIndexOf(ch: Char, fromIndex: Int): Int {
	var vIndex = if (fromIndex >= length) length - 1 else fromIndex
	while (vIndex >= 0) {
		if (this[vIndex] == ch) return vIndex
		vIndex--
	}
	return -1
}

internal actual fun String.nativeIndexOf(str: String, fromIndex: Int): Int {
	var vIndex = if (fromIndex < 0) 0 else fromIndex
	while (vIndex + str.length <= length) {
		if (regionMatches(vIndex, str, 0, str.length, false)) return vIndex
		vIndex++
	}
	return -1
}

internal actual fun String.nativeLastIndexOf(str: String, fromIndex: Int): Int {
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

@PublishedApi
internal actual fun checkRadix(radix: Int): Int {
	if (radix < 2 || radix > 36) throw IllegalArgumentException("radix $radix was not in valid range 2..36")
	return radix
}

internal actual fun digitOf(char: Char, radix: Int): Int {
	val vCode = char.code
	val vDigit = when {
		vCode >= '0'.code && vCode <= '9'.code -> vCode - '0'.code
		vCode >= 'a'.code && vCode <= 'z'.code -> vCode - 'a'.code + 10
		vCode >= 'A'.code && vCode <= 'Z'.code -> vCode - 'A'.code + 10
		else -> -1
	}
	return if (vDigit >= radix) -1 else vDigit
}

private fun numberFormatError(input: String): Nothing = throw NumberFormatException("For input string: \"$input\"")

/** Parses a whole number in [min, max]; fails as `Long.parseLong` does. */
private fun parseWhole(input: String, radix: Int, min: Long, max: Long): Long {
	checkRadix(radix)
	if (input.length == 0) numberFormatError(input)
	var vIndex = 0
	var vNegative = false
	if (input[0] == '-' || input[0] == '+') {
		vNegative = input[0] == '-'
		vIndex = 1
		if (input.length == 1) numberFormatError(input)
	}
	// Accumulate negatively: the magnitude of the minimum is one more than the maximum.
	val vLimit = if (vNegative) min else -max
	var vResult = 0L
	while (vIndex < input.length) {
		val vDigit = digitOf(input[vIndex], radix)
		if (vDigit < 0 || vResult < (vLimit + vDigit) / radix) numberFormatError(input)
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

public actual fun String.toDouble(): Double = toDoubleOrNull() ?: numberFormatError(this)

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

// ==================
// MARK: Regex (declared, not implemented)
// ==================

public actual enum class RegexOption {
	IGNORE_CASE,
	MULTILINE
}

public actual class MatchGroup(public actual val value: String)

private fun regexUnsupported(): Nothing = throw UnsupportedOperationException("Regex is not supported by ktc2 yet")

public actual class Regex actual constructor(pattern: String) {
	public actual val pattern: String = pattern
	public actual val options: Set<RegexOption> get() = regexUnsupported()

	public actual constructor(pattern: String, option: RegexOption) : this(pattern)

	public actual constructor(pattern: String, options: Set<RegexOption>) : this(pattern)

	public actual fun matchEntire(input: CharSequence): MatchResult? = regexUnsupported()
	public actual infix fun matches(input: CharSequence): Boolean = regexUnsupported()
	public actual fun matchAt(input: CharSequence, index: Int): MatchResult? = regexUnsupported()
	public actual fun matchesAt(input: CharSequence, index: Int): Boolean = regexUnsupported()
	public actual fun containsMatchIn(input: CharSequence): Boolean = regexUnsupported()
	public actual fun replace(input: CharSequence, replacement: String): String = regexUnsupported()
	public actual fun replace(input: CharSequence, transform: (MatchResult) -> CharSequence): String = regexUnsupported()
	public actual fun replaceFirst(input: CharSequence, replacement: String): String = regexUnsupported()
	public actual fun find(input: CharSequence, startIndex: Int): MatchResult? = regexUnsupported()
	public actual fun findAll(input: CharSequence, startIndex: Int): Sequence<MatchResult> = regexUnsupported()
	public actual fun split(input: CharSequence, limit: Int): List<String> = regexUnsupported()
	public actual fun splitToSequence(input: CharSequence, limit: Int): Sequence<String> = regexUnsupported()

	public actual companion object {
		public actual fun fromLiteral(literal: String): Regex = regexUnsupported()
		public actual fun escape(literal: String): String = regexUnsupported()
		public actual fun escapeReplacement(literal: String): String = regexUnsupported()
	}
}
