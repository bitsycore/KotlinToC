@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalEncodingApi::class)

package kotlin.io.encoding

/*
ktc2 platform layer: what `kotlin.io.encoding.Base64` leaves to the platform. The library's common
implementations do the work, as on Kotlin/Native.
*/

internal actual fun Base64.platformCharsToBytes(source: CharSequence, startIndex: Int, endIndex: Int): ByteArray =
	charsToBytesImpl(source, startIndex, endIndex)

internal actual fun Base64.platformEncodeToString(source: ByteArray, startIndex: Int, endIndex: Int): String =
	bytesToStringImpl(encodeToByteArrayImpl(source, startIndex, endIndex))

internal actual fun Base64.platformEncodeIntoByteArray(source: ByteArray, destination: ByteArray, destinationOffset: Int, startIndex: Int, endIndex: Int): Int =
	encodeIntoByteArrayImpl(source, destination, destinationOffset, startIndex, endIndex)

internal actual fun Base64.platformEncodeToByteArray(source: ByteArray, startIndex: Int, endIndex: Int): ByteArray =
	encodeToByteArrayImpl(source, startIndex, endIndex)
