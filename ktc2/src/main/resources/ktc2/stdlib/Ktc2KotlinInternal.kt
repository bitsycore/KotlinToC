@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.internal

/** What the compiler calls when no branch of an exhaustive `when` matches (a value added to a sealed type later). */
@PublishedApi
internal actual fun throwNoWhenBranchMatchedException(subject: Any): Nothing {
	throw NoWhenBranchMatchedException("No branch matched for subject: $subject")
}
