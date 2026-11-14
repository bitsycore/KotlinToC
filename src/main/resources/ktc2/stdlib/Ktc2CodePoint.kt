@file:Suppress("ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalUnicodeApi::class)

package kotlin.text.unicode

import kotlin.text.supplementaryCategory
import kotlin.text.supplementaryIsLowerCase
import kotlin.text.supplementaryIsUpperCase
import kotlin.text.supplementaryToLowerCase
import kotlin.text.supplementaryToTitleCase
import kotlin.text.supplementaryToUpperCase

/*
ktc2 platform layer: what `kotlin.text.unicode.CodePoint` leaves to the platform. It answers as the JVM's
`Character` does: below U+10000 as the Char functions, above it from the tables of `Ktc2Unicode.kt`.
*/

private fun outOfBounds(inIndex: Int, inLength: Int): Nothing =
	throw IndexOutOfBoundsException("index: $inIndex, length: $inLength")

/** The code point made of [inHigh] and [inLow], a surrogate pair. */
private fun combined(inHigh: Char, inLow: Char): Int = ((inHigh.code - 0xD800) shl 10) + (inLow.code - 0xDC00) + 0x10000

// ==================
// MARK: Iteration
// ==================

/** The code point at [inIndex] of [inText], reading no further than [inLimit]. */
private fun codePointIn(inText: CharSequence, inIndex: Int, inLimit: Int): CodePoint {
	val vFirst = inText[inIndex]
	if (vFirst.isHighSurrogate() && inIndex + 1 < inLimit) {
		val vSecond = inText[inIndex + 1]
		if (vSecond.isLowSurrogate()) return combined(vFirst, vSecond).toCodePoint()
	}
	return vFirst.code.toCodePoint()
}

/** The code point that ends before [inIndex] of [inText], reading no further back than [inStart]. */
private fun codePointBeforeIn(inText: CharSequence, inIndex: Int, inStart: Int): CodePoint {
	val vSecond = inText[inIndex - 1]
	if (vSecond.isLowSurrogate() && inIndex - 1 > inStart) {
		val vFirst = inText[inIndex - 2]
		if (vFirst.isHighSurrogate()) return combined(vFirst, vSecond).toCodePoint()
	}
	return vSecond.code.toCodePoint()
}

private fun countIn(inText: CharSequence, inStart: Int, inEnd: Int): Int {
	var vCount = inEnd - inStart
	var vIndex = inStart
	while (vIndex < inEnd) {
		if (inText[vIndex].isHighSurrogate() && vIndex + 1 < inEnd && inText[vIndex + 1].isLowSurrogate()) {
			vCount--
			vIndex++
		}
		vIndex++
	}
	return vCount
}

/** [inIndex] moved by [inOffset] code points inside [inStart] until [inEnd] of [inText]. */
private fun offsetIn(inText: CharSequence, inStart: Int, inEnd: Int, inIndex: Int, inOffset: Int): Int {
	var vAt = inIndex
	if (inOffset >= 0) {
		var vMoved = 0
		while (vAt < inEnd && vMoved < inOffset) {
			if (inText[vAt++].isHighSurrogate() && vAt < inEnd && inText[vAt].isLowSurrogate()) vAt++
			vMoved++
		}
		if (vMoved < inOffset) throw IndexOutOfBoundsException("codePointOffset: $inOffset")
	} else {
		var vMoved = inOffset
		while (vAt > inStart && vMoved < 0) {
			if (inText[--vAt].isLowSurrogate() && vAt > inStart && inText[vAt - 1].isHighSurrogate()) vAt--
			vMoved++
		}
		if (vMoved < 0) throw IndexOutOfBoundsException("codePointOffset: $inOffset")
	}
	return vAt
}

/** [inChars] as a CharSequence, read in place. */
private class CharsView(private val chars: CharArray) : CharSequence {
	override val length: Int get() = chars.size
	override fun get(index: Int): Char = chars[index]
	override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = chars.concatToString(startIndex, endIndex)
}

public actual fun String.codePointAt(index: Int): CodePoint {
	if (index < 0 || index >= length) outOfBounds(index, length)
	return codePointIn(this, index, length)
}

public actual fun CharSequence.codePointAt(index: Int): CodePoint {
	if (index < 0 || index >= length) outOfBounds(index, length)
	return codePointIn(this, index, length)
}

public actual fun CharArray.codePointAt(index: Int): CodePoint {
	if (index < 0 || index >= size) outOfBounds(index, size)
	return codePointIn(CharsView(this), index, size)
}

public actual fun CharArray.codePointAt(index: Int, endIndex: Int): CodePoint {
	if (index < 0 || index >= endIndex || endIndex > size) outOfBounds(index, size)
	return codePointIn(CharsView(this), index, endIndex)
}

public actual fun String.codePointBefore(index: Int): CodePoint {
	if (index < 1 || index > length) outOfBounds(index, length)
	return codePointBeforeIn(this, index, 0)
}

public actual fun CharSequence.codePointBefore(index: Int): CodePoint {
	if (index < 1 || index > length) outOfBounds(index, length)
	return codePointBeforeIn(this, index, 0)
}

public actual fun CharArray.codePointBefore(index: Int): CodePoint {
	if (index < 1 || index > size) outOfBounds(index, size)
	return codePointBeforeIn(CharsView(this), index, 0)
}

public actual fun CharArray.codePointBefore(index: Int, startIndex: Int): CodePoint {
	if (index <= startIndex || startIndex < 0 || startIndex >= size || index > size) outOfBounds(index, size)
	return codePointBeforeIn(CharsView(this), index, startIndex)
}

public actual fun String.codePointCount(startIndex: Int, endIndex: Int): Int {
	if (startIndex < 0 || endIndex > length || startIndex > endIndex) throw IndexOutOfBoundsException("startIndex: $startIndex, endIndex: $endIndex, length: $length")
	return countIn(this, startIndex, endIndex)
}

public actual fun CharSequence.codePointCount(startIndex: Int, endIndex: Int): Int {
	if (startIndex < 0 || endIndex > length || startIndex > endIndex) throw IndexOutOfBoundsException("startIndex: $startIndex, endIndex: $endIndex, length: $length")
	return countIn(this, startIndex, endIndex)
}

public actual fun CharArray.codePointCount(startIndex: Int, endIndex: Int): Int {
	if (startIndex < 0 || endIndex > size || startIndex > endIndex) throw IndexOutOfBoundsException("startIndex: $startIndex, endIndex: $endIndex, size: $size")
	return countIn(CharsView(this), startIndex, endIndex)
}

public actual fun String.offsetByCodePoints(index: Int, codePointOffset: Int): Int {
	if (index < 0 || index > length) outOfBounds(index, length)
	return offsetIn(this, 0, length, index, codePointOffset)
}

public actual fun CharSequence.offsetByCodePoints(index: Int, codePointOffset: Int): Int {
	if (index < 0 || index > length) outOfBounds(index, length)
	return offsetIn(this, 0, length, index, codePointOffset)
}

public actual fun CharArray.offsetByCodePoints(index: Int, codePointOffset: Int, startIndex: Int, endIndex: Int): Int {
	if (startIndex < 0 || endIndex > size || startIndex > endIndex || index < startIndex || index > endIndex) outOfBounds(index, size)
	return offsetIn(CharsView(this), startIndex, endIndex, index, codePointOffset)
}

// ==================
// MARK: Properties
// ==================

public actual val CodePoint.category: CharCategory
	get() = if (isBasic) code.toChar().category else CharCategory.entries[supplementaryCategory(code)]

public actual fun CodePoint.isDefined(): Boolean = category != CharCategory.UNASSIGNED

public actual fun CodePoint.isDigit(): Boolean = category == CharCategory.DECIMAL_DIGIT_NUMBER

public actual fun CodePoint.isLetter(): Boolean = when (category) {
	CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
	CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER -> true
	else -> false
}

public actual fun CodePoint.isLetterOrDigit(): Boolean = isLetter() || isDigit()

public actual fun CodePoint.isISOControl(): Boolean = code in 0x00..0x1F || code in 0x7F..0x9F

public actual fun CodePoint.isLowerCase(): Boolean = if (isBasic) code.toChar().isLowerCase() else supplementaryIsLowerCase(code)

public actual fun CodePoint.isUpperCase(): Boolean = if (isBasic) code.toChar().isUpperCase() else supplementaryIsUpperCase(code)

public actual fun CodePoint.isTitleCase(): Boolean = if (isBasic) code.toChar().isTitleCase() else category == CharCategory.TITLECASE_LETTER

/** There is no whitespace above U+FFFF. */
public actual fun CodePoint.isWhitespace(): Boolean = isBasic && code.toChar().isWhitespace()

// ==================
// MARK: Casing
// ==================

public actual fun CodePoint.lowercase(): String = toString().lowercase()

public actual fun CodePoint.lowercaseCodePoint(): CodePoint =
	(if (isBasic) code.toChar().lowercaseChar().code else supplementaryToLowerCase(code)).toCodePoint()

public actual fun CodePoint.uppercase(): String = toString().uppercase()

public actual fun CodePoint.uppercaseCodePoint(): CodePoint =
	(if (isBasic) code.toChar().uppercaseChar().code else supplementaryToUpperCase(code)).toCodePoint()

public actual fun CodePoint.titlecaseCodePoint(): CodePoint =
	(if (isBasic) code.toChar().titlecaseChar().code else supplementaryToTitleCase(code)).toCodePoint()
