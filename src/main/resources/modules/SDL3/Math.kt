@file:cInclude("SDL3/SDL.h")

package sdl3

// ==================
// MARK: FPoint math
// ==================

/** Euclidean distance to another point. */
inline fun SDL3.FPoint.distanceTo(other: SDL3.FPoint): Float {
    val dx = other.x - this.x
    val dy = other.y - this.y
    return c.sqrtf(dx * dx + dy * dy)
}

/** Length of this vector from the origin. */
inline fun SDL3.FPoint.length(): Float = c.sqrtf(this.x * this.x + this.y * this.y)

/** Unit vector in the same direction; returns zero-vector if length is zero. */
inline fun SDL3.FPoint.normalized(): SDL3.FPoint {
    val len = c.sqrtf(this.x * this.x + this.y * this.y)
    if (len == 0.0f) return SDL3.FPoint(0.0f, 0.0f)
    return SDL3.FPoint(this.x / len, this.y / len)
}

/** Component-wise addition. */
inline fun SDL3.FPoint.translate(dx: Float, dy: Float): SDL3.FPoint = SDL3.FPoint(this.x + dx, this.y + dy)

/** Scale by a scalar. */
inline fun SDL3.FPoint.scale(factor: Float): SDL3.FPoint = SDL3.FPoint(this.x * factor, this.y * factor)

/** Dot product with another vector. */
inline fun SDL3.FPoint.dot(other: SDL3.FPoint): Float = this.x * other.x + this.y * other.y

// ==================
// MARK: FRect math
// ==================

/** True if the point lies inside or on the boundary of this rect. */
inline fun SDL3.FRect.contains(point: SDL3.FPoint): Boolean =
    point.x >= this.x && point.x <= this.x + this.w &&
    point.y >= this.y && point.y <= this.y + this.h

/** True if the other rect is fully inside this rect. */
inline fun SDL3.FRect.containsRect(other: SDL3.FRect): Boolean =
    other.x >= this.x && other.y >= this.y &&
    other.x + other.w <= this.x + this.w &&
    other.y + other.h <= this.y + this.h

/** True if this rect overlaps the other (touching edges count as overlap). */
inline fun SDL3.FRect.intersects(other: SDL3.FRect): Boolean =
    this.x <= other.x + other.w && this.x + this.w >= other.x &&
    this.y <= other.y + other.h && this.y + this.h >= other.y

/** Move the rect by (dx, dy). */
inline fun SDL3.FRect.translate(dx: Float, dy: Float): SDL3.FRect =
    SDL3.FRect(this.x + dx, this.y + dy, this.w, this.h)

/** Expand rect outward by amount on all sides. */
inline fun SDL3.FRect.grow(amount: Float): SDL3.FRect =
    SDL3.FRect(this.x - amount, this.y - amount, this.w + amount * 2.0f, this.h + amount * 2.0f)

/** Shrink rect inward by amount on all sides. */
inline fun SDL3.FRect.shrink(amount: Float): SDL3.FRect =
    SDL3.FRect(this.x + amount, this.y + amount, this.w - amount * 2.0f, this.h - amount * 2.0f)

/** Center point of the rect. */
inline fun SDL3.FRect.center(): SDL3.FPoint = SDL3.FPoint(this.x + this.w / 2.0f, this.y + this.h / 2.0f)

/** Move rect so that its center is at the given point. */
inline fun SDL3.FRect.centerOn(cx: Float, cy: Float): SDL3.FRect =
    SDL3.FRect(cx - this.w / 2.0f, cy - this.h / 2.0f, this.w, this.h)

// ==================
// MARK: Scalar helpers
// ==================

/** Linear interpolation between a and b by t (0..1). */
inline fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Clamp value to [lo, hi]. */
inline fun clamp(value: Float, lo: Float, hi: Float): Float =
    if (value < lo) lo else if (value > hi) hi else value

/** Clamp value to [lo, hi]. */
inline fun clamp(value: Int, lo: Int, hi: Int): Int =
    if (value < lo) lo else if (value > hi) hi else value

// ==================
// MARK: FPoint lerp / clamp
// ==================

/** Linearly interpolate between two points. */
inline fun SDL3.FPoint.lerpTo(other: SDL3.FPoint, t: Float): SDL3.FPoint =
    SDL3.FPoint(lerp(this.x, other.x, t), lerp(this.y, other.y, t))

/** Clamp point so that it stays inside the given rect. */
inline fun SDL3.FPoint.clampTo(rect: SDL3.FRect): SDL3.FPoint =
    SDL3.FPoint(
        clamp(this.x, rect.x, rect.x + rect.w),
        clamp(this.y, rect.y, rect.y + rect.h)
    )
