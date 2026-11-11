@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.internal

/** What the compiler calls when no branch of an exhaustive `when` matches (a value added to a sealed type later). */
@PublishedApi
internal actual fun throwNoWhenBranchMatchedException(subject: Any): Nothing {
	throw NoWhenBranchMatchedException("No branch matched for subject: $subject")
}

// ==================
// MARK: Serialization hooks
// ==================

/*
The standard library's classes carry hooks for Java serialization, which does not exist here.
*/

internal actual inline fun throwReadObjectNotSupported(): Nothing = throw UnsupportedOperationException("Deserialization is not supported")

internal actual inline fun wrapAsDeserializationException(action: () -> Unit) {
	action()
}

internal actual class ReadObjectParameterType
