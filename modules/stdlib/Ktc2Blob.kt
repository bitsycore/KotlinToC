@file:Suppress("DEPRECATION_ERROR")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kotlin.native

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.internal.cExpr
import ktc2.internal.throwIndexOutOfBounds

/*
ktc2 platform layer: Kotlin/Native's ImmutableBlob (kotlin-native/runtime/src/main/kotlin/kotlin/native/Blob.kt),
deprecated since Kotlin 1.9 and an error since 2.1, still part of Native's API. Native keeps the bytes of
`immutableBlobOf` in the program's constant data; here a blob holds them in a ByteArray, so a pointer of
[asCPointer] stays valid as long as the blob does (Native's own rule: the caller keeps the blob).
*/

@Deprecated("Use ByteArray instead.")
@DeprecatedSinceKotlin(warningSince = "1.9", errorSince = "2.1")
public class ImmutableBlob internal constructor(internal val mBytes: ByteArray) {
	public val size: Int
		get() = mBytes.size

	public operator fun get(index: Int): Byte = mBytes[index]

	public operator fun iterator(): ByteIterator = mBytes.iterator()
}

private fun ImmutableBlob.checkSlice(startIndex: Int, endIndex: Int) {
	if (startIndex < 0 || endIndex > size || startIndex > endIndex) throwIndexOutOfBounds(startIndex, size)
}

@Deprecated("ImmutableBlob is deprecated. Use ByteArray instead.")
@DeprecatedSinceKotlin(warningSince = "1.9", errorSince = "2.1")
public fun ImmutableBlob.toByteArray(startIndex: Int = 0, endIndex: Int = size): ByteArray {
	checkSlice(startIndex, endIndex)
	return mBytes.copyOfRange(startIndex, endIndex)
}

@Deprecated("ImmutableBlob is deprecated. Use ByteArray instead.")
@DeprecatedSinceKotlin(warningSince = "1.9", errorSince = "2.1")
@ExperimentalUnsignedTypes
public fun ImmutableBlob.toUByteArray(startIndex: Int = 0, endIndex: Int = size): UByteArray {
	checkSlice(startIndex, endIndex)
	return mBytes.copyOfRange(startIndex, endIndex).asUByteArray()
}

@Deprecated("ImmutableBlob is deprecated. Use ByteArray instead. To get a stable C pointer to a `ByteArray`, pin it first.")
@DeprecatedSinceKotlin(warningSince = "1.9", errorSince = "2.1")
public fun ImmutableBlob.asCPointer(offset: Int = 0): CPointer<ByteVar> {
	if (offset < 0 || offset > size) throwIndexOutOfBounds(offset, size)
	return cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1)", mBytes, offset)
}

@Deprecated("ImmutableBlob is deprecated. Use ByteArray instead. To get a stable C pointer to a `ByteArray`, pin it first.")
@DeprecatedSinceKotlin(warningSince = "1.9", errorSince = "2.1")
public fun ImmutableBlob.asUCPointer(offset: Int = 0): CPointer<UByteVar> {
	if (offset < 0 || offset > size) throwIndexOutOfBounds(offset, size)
	return cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1)", mBytes, offset)
}

@Deprecated("ImmutableBlob is deprecated. Use ByteArray instead.", ReplaceWith("byteArrayOf(*elements)"))
@DeprecatedSinceKotlin(warningSince = "1.9", errorSince = "2.1")
public fun immutableBlobOf(vararg elements: Short): ImmutableBlob = ImmutableBlob(ByteArray(elements.size) { elements[it].toByte() })
