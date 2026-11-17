@file:Suppress("ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.text

import kotlin.experimental.ExperimentalNativeApi

/*
ktc2 platform layer: what Kotlin/Native's character code (nativeWasmMain/kotlin/text/Char.kt) leaves
to the platform, as Kotlin/Native's runtime has it (CharNative.kt): public there, with
`ExperimentalNativeApi`.
*/

/** The code point of the surrogate pair [high], [low]. */
@ExperimentalNativeApi
public actual fun Char.Companion.toCodePoint(high: Char, low: Char): Int =
	(((high - '\uD800') shl 10) or (low - '\uDC00')) + 0x10000

/** Whether [codepoint] is above U+FFFF and a code point. */
@ExperimentalNativeApi
public actual fun Char.Companion.isSupplementaryCodePoint(codepoint: Int): Boolean = codepoint in 0x10000..0x10FFFF

/** Whether [high] and [low] are a high and a low surrogate. */
@ExperimentalNativeApi
public actual fun Char.Companion.isSurrogatePair(high: Char, low: Char): Boolean = high.isHighSurrogate() && low.isLowSurrogate()

/** [codePoint] as one Char, or two (a surrogate pair) above U+FFFF. */
@ExperimentalNativeApi
public actual fun Char.Companion.toChars(codePoint: Int): CharArray =
	when {
		codePoint in 0 until 0x10000 -> charArrayOf(codePoint.toChar())
		codePoint in 0x10000..0x10FFFF -> {
			val vLow = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
			val vHigh = (((codePoint - 0x10000) ushr 10) and 0x3FF) + 0xD800
			charArrayOf(vHigh.toChar(), vLow.toChar())
		}
		else -> throw IllegalArgumentException()
	}
