@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "ACTUAL_WITHOUT_EXPECT", "UNUSED_PARAMETER")

package kotlin.enums

/*
What the standard library's EnumEntries.kt leaves to its platform.
*/

/** `enumEntries<T>()` needs the enum class at run time, which a type parameter does not carry here. */
internal actual fun <T : Enum<T>> enumEntriesIntrinsic(): EnumEntries<T> =
	throw UnsupportedOperationException("enumEntries<T>() is not supported; use the entries of the enum class itself")

/** A hook for Java serialization, which does not exist here. */
internal actual class EnumEntriesSerializationProxy<E : Enum<E>> actual constructor(entries: Array<E>)
