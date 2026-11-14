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

Classification and case of characters follow Unicode for everything a Char can hold, from the
tables of Ktc2Unicode.kt, with a short way for ASCII. Characters beyond U+FFFF, which a string
holds as two Chars, are left as they are by the case functions. Regex is in Ktc2Regex.kt.
*/

// ==================
// MARK: Char
// ==================

public actual fun Char.isHighSurrogate(): Boolean = this >= '\uD800' && this <= '\uDBFF'

public actual fun Char.isLowSurrogate(): Boolean = this >= '\uDC00' && this <= '\uDFFF'

public actual fun Char.lowercaseChar(): Char {
	val vCode = code
	if (vCode < 0x80) return if (vCode >= 'A'.code && vCode <= 'Z'.code) (vCode + 32).toChar() else this
	return unicodeToLowerCase(this)
}

public actual fun Char.uppercaseChar(): Char {
	val vCode = code
	if (vCode < 0x80) return if (vCode >= 'a'.code && vCode <= 'z'.code) (vCode - 32).toChar() else this
	return unicodeToUpperCase(this)
}

public actual fun Char.toLowerCase(): Char = lowercaseChar()

public actual fun Char.toUpperCase(): Char = uppercaseChar()

/** The lowercase form as a string: one character, except for `İ`, which gives an `i` and a dot above. */
public actual fun Char.lowercase(): String = if (this == '\u0130') "i\u0307" else lowercaseChar().toString()

/** The uppercase form as a string, which for some characters is several (`ß` gives `SS`). */
public actual fun Char.uppercase(): String = unicodeUpperCaseString(this) ?: uppercaseChar().toString()

public actual fun Char.titlecaseChar(): Char {
	val vCode = code
	if (vCode < 0x80) return if (vCode >= 'a'.code && vCode <= 'z'.code) (vCode - 32).toChar() else this
	return unicodeToTitleCase(this)
}

public actual fun Char.isDigit(): Boolean {
	if (code < 0x80) return this >= '0' && this <= '9'
	return unicodeCategory(this) == kDecimalDigit
}

public actual fun Char.isUpperCase(): Boolean {
	val vCode = code
	if (vCode < 0x80) return vCode >= 'A'.code && vCode <= 'Z'.code
	return unicodeIsUpperCase(this)
}

public actual fun Char.isLowerCase(): Boolean {
	val vCode = code
	if (vCode < 0x80) return vCode >= 'a'.code && vCode <= 'z'.code
	return unicodeIsLowerCase(this)
}

public actual fun Char.isTitleCase(): Boolean = code >= 0x80 && unicodeCategory(this) == kTitlecaseLetter

public actual fun Char.isLetter(): Boolean {
	val vCode = code
	if (vCode < 0x80) return (vCode >= 'A'.code && vCode <= 'Z'.code) || (vCode >= 'a'.code && vCode <= 'z'.code)
	val vCategory = unicodeCategory(this)
	return vCategory >= kUppercaseLetter && vCategory <= kOtherLetter
}

public actual fun Char.isLetterOrDigit(): Boolean = isLetter() || isDigit()

public actual fun Char.isDefined(): Boolean = code < 0x80 || unicodeCategory(this) != kUnassigned

public actual fun Char.isISOControl(): Boolean = code <= 0x1F || (code >= 0x7F && code <= 0x9F)

public actual fun Char.isWhitespace(): Boolean {
	val vCode = code
	if (vCode < 0x80) return (vCode >= 0x09 && vCode <= 0x0D) || (vCode >= 0x1C && vCode <= 0x20)
	return unicodeIsWhitespace(this)
}

public actual val Char.category: CharCategory get() = CharCategory.entries[unicodeCategory(this)]

// Ordinals of CharCategory, which is what the tables hold.
private const val kUnassigned = 0
private const val kUppercaseLetter = 1
private const val kTitlecaseLetter = 3
private const val kOtherLetter = 5
private const val kNonSpacingMark = 6
private const val kEnclosingMark = 7
private const val kDecimalDigit = 9
private const val kFormat = 16
private const val kModifierLetter = 4
private const val kModifierSymbol = 26

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

/**
 * When the two code units at [inIndex] of [inText] are one letter outside the first 65536 characters:
 * that letter in the other case appended to [inBuilder], and true.
 */
private fun appendSupplementary(inText: String, inIndex: Int, inBuilder: StringBuilder, inIsUpper: Boolean): Boolean {
	val vHigh = inText[inIndex]
	if (!vHigh.isHighSurrogate() || inIndex + 1 >= inText.length) return false
	val vLow = inText[inIndex + 1]
	if (!vLow.isLowSurrogate()) return false
	val vCodePoint = 0x10000 + ((vHigh.code - 0xD800) shl 10) + (vLow.code - 0xDC00)
	val vOther = (if (inIsUpper) supplementaryToUpperCase(vCodePoint) else supplementaryToLowerCase(vCodePoint)) - 0x10000
	inBuilder.append((0xD800 + (vOther shr 10)).toChar()).append((0xDC00 + (vOther and 0x3FF)).toChar())
	return true
}

public actual fun String.uppercase(): String {
	val vBuilder = StringBuilder(length)
	var vIndex = -1
	while (++vIndex < length) {
		val vChar = this[vIndex]
		if (vChar.isHighSurrogate() && appendSupplementary(this, vIndex, vBuilder, true)) {
			vIndex++
			continue
		}
		// ASCII, by far the most of what is converted, has no form of several characters.
		val vSeveral = if (vChar.code < 0x80) null else unicodeUpperCaseString(vChar)
		if (vSeveral != null) vBuilder.append(vSeveral) else vBuilder.append(vChar.uppercaseChar())
	}
	return vBuilder.toString()
}

public actual fun String.lowercase(): String {
	val vBuilder = StringBuilder(length)
	var vIndex = -1
	while (++vIndex < length) {
		val vChar = this[vIndex]
		if (vChar.isHighSurrogate() && appendSupplementary(this, vIndex, vBuilder, false)) {
			vIndex++
			continue
		}
		when {
			vChar == '\u0130' -> vBuilder.append("i\u0307")
			// A capital sigma that ends a word is written with the final form of the letter.
			vChar == '\u03A3' && isFinalSigma(this, vIndex) -> vBuilder.append('\u03C2')
			else -> vBuilder.append(vChar.lowercaseChar())
		}
	}
	return vBuilder.toString()
}

private fun isCased(inChar: Char): Boolean = inChar.isUpperCase() || inChar.isLowerCase() || inChar.isTitleCase()

/** True for what does not count when looking for the letters around a sigma: marks, format characters, some punctuation. */
private fun isCaseIgnorable(inChar: Char): Boolean {
	if (inChar == '\'' || inChar == '.' || inChar == ':' || inChar == '^' || inChar == '`' || inChar == '\u00B7' || inChar == '\u2019') return true
	if (inChar.code < 0x80) return false
	val vCategory = unicodeCategory(inChar)
	return vCategory == kNonSpacingMark || vCategory == kEnclosingMark || vCategory == kFormat || vCategory == kModifierLetter || vCategory == kModifierSymbol
}

/** True when the sigma at [inIndex] of [inText] comes after a letter and no letter comes after it. */
private fun isFinalSigma(inText: String, inIndex: Int): Boolean {
	var vBefore = inIndex - 1
	while (vBefore >= 0 && isCaseIgnorable(inText[vBefore])) vBefore--
	if (vBefore < 0 || !isCased(inText[vBefore])) return false
	var vAfter = inIndex + 1
	while (vAfter < inText.length && isCaseIgnorable(inText[vAfter])) vAfter++
	return vAfter >= inText.length || !isCased(inText[vAfter])
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
		vCode < 0x80 -> -1
		// The letters of full width count as the plain ones do; the digits of every script as digits.
		vCode >= 0xFF21 && vCode <= 0xFF3A -> vCode - 0xFF21 + 10
		vCode >= 0xFF41 && vCode <= 0xFF5A -> vCode - 0xFF41 + 10
		else -> unicodeDigit(char)
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
