package ktc

// ==================
// MARK: Math (pure inline)
// ==================
// Zero-overhead, zero-dependency numeric helpers. All inline, so they expand at the call site and
// add nothing to the binary unless used. (Transcendental functions - sqrt/pow/sin/cos - are NOT
// here: they need <math.h> in every calling TU, a decision deferred per plan.md U4. Until then,
// call C.sqrtf(...) etc. directly.)

// ── maxOf / minOf ─────────────────────────────────────────────────

/** The greater of [a] and [b]. */
inline fun maxOf(a: Int, b: Int): Int = if (a >= b) a else b
inline fun maxOf(a: Long, b: Long): Long = if (a >= b) a else b
inline fun maxOf(a: Float, b: Float): Float = if (a >= b) a else b
inline fun maxOf(a: Double, b: Double): Double = if (a >= b) a else b

/** The smaller of [a] and [b]. */
inline fun minOf(a: Int, b: Int): Int = if (a <= b) a else b
inline fun minOf(a: Long, b: Long): Long = if (a <= b) a else b
inline fun minOf(a: Float, b: Float): Float = if (a <= b) a else b
inline fun minOf(a: Double, b: Double): Double = if (a <= b) a else b

// ── abs ───────────────────────────────────────────────────────────
// `0 - x` rather than `-x` to avoid depending on a unaryMinus operator intrinsic.

/** Absolute value of [x]. */
inline fun abs(x: Int): Int = if (x < 0) 0 - x else x
inline fun abs(x: Long): Long = if (x < 0L) 0L - x else x
inline fun abs(x: Float): Float = if (x < 0.0f) 0.0f - x else x
inline fun abs(x: Double): Double = if (x < 0.0) 0.0 - x else x

// ── coerceIn / coerceAtLeast / coerceAtMost ───────────────────────

/** Returns this value clamped to the inclusive range [[min], [max]]. */
inline fun Int.coerceIn(min: Int, max: Int): Int = if (this < min) min else if (this > max) max else this
inline fun Long.coerceIn(min: Long, max: Long): Long = if (this < min) min else if (this > max) max else this
inline fun Float.coerceIn(min: Float, max: Float): Float = if (this < min) min else if (this > max) max else this
inline fun Double.coerceIn(min: Double, max: Double): Double = if (this < min) min else if (this > max) max else this

/** Returns this value if it is >= [min], otherwise [min]. */
inline fun Int.coerceAtLeast(min: Int): Int = if (this < min) min else this
inline fun Long.coerceAtLeast(min: Long): Long = if (this < min) min else this
inline fun Float.coerceAtLeast(min: Float): Float = if (this < min) min else this
inline fun Double.coerceAtLeast(min: Double): Double = if (this < min) min else this

/** Returns this value if it is <= [max], otherwise [max]. */
inline fun Int.coerceAtMost(max: Int): Int = if (this > max) max else this
inline fun Long.coerceAtMost(max: Long): Long = if (this > max) max else this
inline fun Float.coerceAtMost(max: Float): Float = if (this > max) max else this
inline fun Double.coerceAtMost(max: Double): Double = if (this > max) max else this
