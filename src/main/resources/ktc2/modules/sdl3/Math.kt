package ktc2.sdl3

// ==================
// MARK: FPoint math
// ==================

/** Euclidean distance to another point. */
fun SDL3.FPoint.distanceTo(other: SDL3.FPoint): Float {
	val vDx = other.x - x
	val vDy = other.y - y
	return squareRoot(vDx * vDx + vDy * vDy)
}

/** Length of this vector from the origin. */
fun SDL3.FPoint.length(): Float = squareRoot(x * x + y * y)

/** Unit vector in the same direction; returns zero-vector if length is zero. */
fun SDL3.FPoint.normalized(): SDL3.FPoint {
	val vLength = length()
	if (vLength == 0.0f) return SDL3.FPoint(0.0f, 0.0f)
	return SDL3.FPoint(x / vLength, y / vLength)
}

/** Component-wise addition. */
fun SDL3.FPoint.translate(dx: Float, dy: Float): SDL3.FPoint = SDL3.FPoint(x + dx, y + dy)

/** Scale by a scalar. */
fun SDL3.FPoint.scale(factor: Float): SDL3.FPoint = SDL3.FPoint(x * factor, y * factor)

/** Dot product with another vector. */
fun SDL3.FPoint.dot(other: SDL3.FPoint): Float = x * other.x + y * other.y

// ==================
// MARK: FRect math
// ==================

/** True if the point lies inside or on the boundary of this rect. */
fun SDL3.FRect.contains(point: SDL3.FPoint): Boolean =
	point.x >= x && point.x <= x + w && point.y >= y && point.y <= y + h

/** True if the other rect is fully inside this rect. */
fun SDL3.FRect.containsRect(other: SDL3.FRect): Boolean =
	other.x >= x && other.y >= y && other.x + other.w <= x + w && other.y + other.h <= y + h

/** True if this rect overlaps the other (touching edges count as overlap). */
fun SDL3.FRect.intersects(other: SDL3.FRect): Boolean =
	x <= other.x + other.w && x + w >= other.x && y <= other.y + other.h && y + h >= other.y

/** Move the rect by (dx, dy). */
fun SDL3.FRect.translate(dx: Float, dy: Float): SDL3.FRect = SDL3.FRect(x + dx, y + dy, w, h)

/** Expand rect outward by amount on all sides. */
fun SDL3.FRect.grow(amount: Float): SDL3.FRect = SDL3.FRect(x - amount, y - amount, w + amount * 2.0f, h + amount * 2.0f)

/** Shrink rect inward by amount on all sides. */
fun SDL3.FRect.shrink(amount: Float): SDL3.FRect = SDL3.FRect(x + amount, y + amount, w - amount * 2.0f, h - amount * 2.0f)

/** Center point of the rect. */
fun SDL3.FRect.center(): SDL3.FPoint = SDL3.FPoint(x + w / 2.0f, y + h / 2.0f)

/** Move rect so that its center is at the given point. */
fun SDL3.FRect.centerOn(cx: Float, cy: Float): SDL3.FRect = SDL3.FRect(cx - w / 2.0f, cy - h / 2.0f, w, h)

// ==================
// MARK: Scalar helpers
// ==================

/** Linear interpolation between a and b by t (0..1). */
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Clamp value to [lo, hi]. */
fun clamp(value: Float, lo: Float, hi: Float): Float = if (value < lo) lo else if (value > hi) hi else value

/** Clamp value to [lo, hi]. */
fun clamp(value: Int, lo: Int, hi: Int): Int = if (value < lo) lo else if (value > hi) hi else value

// ==================
// MARK: FPoint lerp / clamp
// ==================

/** Linearly interpolate between two points. */
fun SDL3.FPoint.lerpTo(other: SDL3.FPoint, t: Float): SDL3.FPoint = SDL3.FPoint(lerp(x, other.x, t), lerp(y, other.y, t))

/** Clamp point so that it stays inside the given rect. */
fun SDL3.FPoint.clampTo(rect: SDL3.FRect): SDL3.FPoint =
	SDL3.FPoint(clamp(x, rect.x, rect.x + rect.w), clamp(y, rect.y, rect.y + rect.h))
