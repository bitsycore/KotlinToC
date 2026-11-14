@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalUuidApi::class)

package kotlin.uuid

/*
ktc2 platform layer: what `kotlin.uuid.Uuid` leaves to the platform. The library's common implementations
do the work; the random bytes of `Uuid.random()` come from the operating system.
*/

/** What the JVM writes in place of a Uuid when it serializes one: there is no serialization of objects here. */
internal actual fun serializedUuid(uuid: Uuid): Any = throw UnsupportedOperationException("serialization of objects")

internal actual fun secureRandomBytes(destination: ByteArray): Unit = ktc2.internal.secureRandomBytes(destination)

internal actual fun ByteArray.getLongAt(index: Int): Long = getLongAtCommonImpl(index)

internal actual fun Long.formatBytesInto(dst: ByteArray, dstOffset: Int, startIndex: Int, endIndex: Int) =
	formatBytesIntoCommonImpl(dst, dstOffset, startIndex, endIndex)

internal actual fun ByteArray.setLongAt(index: Int, value: Long) = setLongAtCommonImpl(index, value)

internal actual fun uuidParseHexDash(hexDashString: String): Uuid = uuidParseHexDashCommonImpl(hexDashString)

internal actual fun uuidParseHexDashOrNull(hexDashString: String): Uuid? = uuidParseHexDashOrNullCommonImpl(hexDashString)

internal actual fun uuidParseHex(hexString: String): Uuid = uuidParseHexCommonImpl(hexString)

internal actual fun uuidParseHexOrNull(hexString: String): Uuid? = uuidParseHexOrNullCommonImpl(hexString)
