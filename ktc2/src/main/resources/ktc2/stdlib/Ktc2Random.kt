@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "ACTUAL_WITHOUT_EXPECT")
@file:kotlinx.cinterop.internal.CHeaders("<time.h>")

package kotlin.random

import kotlinx.cinterop.internal.cExpr

/*
What kotlin.random leaves to its platform. The generator itself (XorWow) is the standard
library's own; only its seed comes from here.
*/

/** `Random.Default`: seeded from the clock when the program starts. */
internal actual val defaultRandom: Random = XorWowRandom(cExpr<Long>("(int64_t)time(NULL)").toInt(), cExpr<Long>("(int64_t)clock()").toInt())

internal actual fun doubleFromParts(hi26: Int, low27: Int): Double =
	((hi26.toLong() shl 27) + low27) / (1L shl 53).toDouble()
