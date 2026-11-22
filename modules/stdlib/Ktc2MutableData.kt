@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kotlin.native.concurrent

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.internal.cExpr
import kotlinx.cinterop.usePinned

/*
ktc2 platform layer: Kotlin/Native's MutableData (kotlin-native/runtime/src/main/kotlin/kotlin/native/concurrent/
MutableData.kt), deprecated since the legacy memory manager and an error since Kotlin 2.1, still part of Native's API:
a buffer of bytes that grows, each operation under its lock.
*/

@Deprecated("Support for the legacy memory manager has been completely removed. Use any regular collection instead.")
@DeprecatedSinceKotlin(errorSince = "2.1")
public class MutableData constructor(capacity: Int = 16) {
	init {
		if (capacity <= 0) throw IllegalArgumentException()
	}

	private var mBuffer = ByteArray(capacity)
	private var mSize = 0
	private val mLock = Any()

	private fun resizeDataLocked(inNewSize: Int): Int {
		if (inNewSize > mBuffer.size) {
			val vNewBuffer = ByteArray(maxOf(mBuffer.size * 3 / 2 + 1, inNewSize))
			mBuffer.copyInto(vNewBuffer, startIndex = 0, endIndex = mSize)
			mBuffer = vNewBuffer
		}
		val vPosition = mSize
		mSize = inNewSize
		return vPosition
	}

	public val size: Int
		get() = mSize

	public fun reset(): Unit = synchronized(mLock) {
		mSize = 0
	}

	@Suppress("DEPRECATION_ERROR")
	public fun append(data: MutableData): Unit = synchronized(mLock) {
		val vCount = data.size
		val vWhere = resizeDataLocked(mSize + vCount)
		data.copyInto(mBuffer, vWhere, 0, vCount)
	}

	public fun append(data: ByteArray, fromIndex: Int = 0, toIndex: Int = data.size): Unit = synchronized(mLock) {
		if (fromIndex > toIndex) throw IndexOutOfBoundsException("$fromIndex is bigger than $toIndex")
		if (fromIndex == toIndex) return
		val vWhere = resizeDataLocked(mSize + (toIndex - fromIndex))
		data.copyInto(mBuffer, vWhere, fromIndex, toIndex)
	}

	public fun append(data: COpaquePointer?, count: Int): Unit = synchronized(mLock) {
		if (data == null || count <= 0) return
		val vWhere = resizeDataLocked(mSize + count)
		cExpr<Unit>("memcpy((char*)((ktc2_Array*)$0 + 1) + (size_t)$1, $2, (size_t)$3)", mBuffer, vWhere, data, count)
	}

	public fun copyInto(output: ByteArray, destinationIndex: Int, startIndex: Int, endIndex: Int): Unit = synchronized(mLock) {
		mBuffer.copyInto(output, destinationIndex, startIndex, endIndex)
	}

	public operator fun get(index: Int): Byte = synchronized(mLock) {
		if (index >= mSize) throw IndexOutOfBoundsException("$index is not below $mSize")
		mBuffer[index]
	}

	public fun <R> withPointerLocked(block: (COpaquePointer, dataSize: Int) -> R): R = synchronized(mLock) {
		mBuffer.usePinned { block(it.addressOf(0), mSize) }
	}

	public fun <R> withBufferLocked(block: (array: ByteArray, dataSize: Int) -> R): R = synchronized(mLock) {
		block(mBuffer, mSize)
	}
}
