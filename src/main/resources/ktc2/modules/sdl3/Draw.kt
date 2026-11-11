@file:OptIn(ExperimentalForeignApi::class)

package ktc.sdl3

import kotlinx.cinterop.*
import sdl3.*

// ==================
// MARK: Draw shapes
// ==================

/** Draw a circle outline using the midpoint circle algorithm. */
fun SDL3.Renderer.drawCircle(cx: Float, cy: Float, radius: Float) {
	var vX = 0.0f
	var vY = radius
	var vDecision = 1.25f - radius
	while (vX <= vY) {
		SDL_RenderPoint(handle, cx + vX, cy + vY)
		SDL_RenderPoint(handle, cx - vX, cy + vY)
		SDL_RenderPoint(handle, cx + vX, cy - vY)
		SDL_RenderPoint(handle, cx - vX, cy - vY)
		SDL_RenderPoint(handle, cx + vY, cy + vX)
		SDL_RenderPoint(handle, cx - vY, cy + vX)
		SDL_RenderPoint(handle, cx + vY, cy - vX)
		SDL_RenderPoint(handle, cx - vY, cy - vX)
		if (vDecision < 0.0f) {
			vDecision += 2.0f * vX + 3.0f
		} else {
			vDecision += 2.0f * (vX - vY) + 5.0f
			vY -= 1.0f
		}
		vX += 1.0f
	}
}

/** Fill a circle using horizontal scanlines. */
fun SDL3.Renderer.fillCircle(cx: Float, cy: Float, radius: Float) {
	val vLine = onStack<SDL_FRect>()
	vLine.h = 1.0f
	var vY = -radius
	while (vY <= radius) {
		val vHalfWidth = squareRoot(radius * radius - vY * vY)
		vLine.x = cx - vHalfWidth
		vLine.y = cy + vY
		vLine.w = vHalfWidth * 2.0f
		SDL_RenderFillRect(handle, vLine.ptr)
		vY += 1.0f
	}
}

// ==================
// MARK: Rounded rectangles
// ==================

private fun cornerRadius(inRect: SDL3.FRect, inRadius: Float): Float =
	if (inRadius > inRect.w / 2.0f) inRect.w / 2.0f else if (inRadius > inRect.h / 2.0f) inRect.h / 2.0f else inRadius

/**
 * Fill a rectangle with rounded corners.
 * All four corners are filled in one pass: centre + side strips + corner scanlines.
 */
fun SDL3.Renderer.fillRoundedRect(rect: SDL3.FRect, radius: Float) {
	val vRadius = cornerRadius(rect, radius)
	val vPart = onStack<SDL_FRect>()

	/** Fills one piece through the same C struct. */
	fun piece(inX: Float, inY: Float, inWidth: Float, inHeight: Float) {
		vPart.x = inX
		vPart.y = inY
		vPart.w = inWidth
		vPart.h = inHeight
		SDL_RenderFillRect(handle, vPart.ptr)
	}

	// Centre column (full height, between the corner arcs)
	piece(rect.x + vRadius, rect.y, rect.w - vRadius * 2.0f, rect.h)
	// Left and right strips (inner band, excluding the corner arcs)
	piece(rect.x, rect.y + vRadius, vRadius, rect.h - vRadius * 2.0f)
	piece(rect.x + rect.w - vRadius, rect.y + vRadius, vRadius, rect.h - vRadius * 2.0f)
	// Corner arcs - scanline per row, all 4 corners in one loop
	var vRow = 0.0f
	while (vRow <= vRadius) {
		val vHalfWidth = squareRoot(vRadius * vRadius - vRow * vRow)
		piece(rect.x + vRadius - vHalfWidth, rect.y + vRadius - vRow, vHalfWidth, 1.0f)
		piece(rect.x + rect.w - vRadius, rect.y + vRadius - vRow, vHalfWidth, 1.0f)
		piece(rect.x + vRadius - vHalfWidth, rect.y + rect.h - vRadius + vRow, vHalfWidth, 1.0f)
		piece(rect.x + rect.w - vRadius, rect.y + rect.h - vRadius + vRow, vHalfWidth, 1.0f)
		vRow += 1.0f
	}
}

/**
 * Draw the outline of a rectangle with rounded corners.
 * Straight edges + all 4 corner arcs drawn in one midpoint-circle pass.
 *
 * Every pixel of the outline (edges and corner arcs) is drawn via SDL_RenderPoint
 * so the rasterization is uniform. SDL_RenderLine and SDL_RenderFillRect both
 * round float coords differently from SDL_RenderPoint (line: centered 1-pixel
 * band; fillrect: pixel-center-inside-bounds rounds y *up*; point: floor),
 * which produces a 1-pixel offset between straight edges and corner arcs on
 * animated/fractional rects.
 */
fun SDL3.Renderer.drawRoundedRect(rect: SDL3.FRect, radius: Float) {
	val vRadius = cornerRadius(rect, radius)
	// Straight edges as point loops - same rasterizer as the corner arcs below.
	var vEdgeX = rect.x + vRadius
	while (vEdgeX <= rect.x + rect.w - vRadius) {
		SDL_RenderPoint(handle, vEdgeX, rect.y)
		SDL_RenderPoint(handle, vEdgeX, rect.y + rect.h)
		vEdgeX += 1.0f
	}
	var vEdgeY = rect.y + vRadius
	while (vEdgeY <= rect.y + rect.h - vRadius) {
		SDL_RenderPoint(handle, rect.x, vEdgeY)
		SDL_RenderPoint(handle, rect.x + rect.w, vEdgeY)
		vEdgeY += 1.0f
	}
	// Corner arc centres
	val vLeft = rect.x + vRadius
	val vRight = rect.x + rect.w - vRadius
	val vTop = rect.y + vRadius
	val vBottom = rect.y + rect.h - vRadius
	// Midpoint circle - all 4 corners per step
	var vX = 0.0f
	var vY = vRadius
	var vDecision = 1.25f - vRadius
	while (vX <= vY) {
		SDL_RenderPoint(handle, vLeft - vX, vTop - vY)
		SDL_RenderPoint(handle, vLeft - vY, vTop - vX)
		SDL_RenderPoint(handle, vRight + vX, vTop - vY)
		SDL_RenderPoint(handle, vRight + vY, vTop - vX)
		SDL_RenderPoint(handle, vLeft - vX, vBottom + vY)
		SDL_RenderPoint(handle, vLeft - vY, vBottom + vX)
		SDL_RenderPoint(handle, vRight + vX, vBottom + vY)
		SDL_RenderPoint(handle, vRight + vY, vBottom + vX)
		if (vDecision < 0.0f) {
			vDecision += 2.0f * vX + 3.0f
		} else {
			vDecision += 2.0f * (vX - vY) + 5.0f
			vY -= 1.0f
		}
		vX += 1.0f
	}
}

// ==================
// MARK: Triangles (SDL_RenderGeometry)
// ==================

/**
 * Fill a triangle with per-vertex RGBA float colors [0..1].
 * Uses SDL_RenderGeometry - blending must be set before calling if transparency is needed.
 */
fun SDL3.Renderer.fillTriangle(
	x0: Float, y0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
	x1: Float, y1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
	x2: Float, y2: Float, r2: Float, g2: Float, b2: Float, a2: Float,
) {
	val vVertices = onStackArray<SDL_Vertex>(3)

	fun vertex(inIndex: Int, inX: Float, inY: Float, inRed: Float, inGreen: Float, inBlue: Float, inAlpha: Float) {
		val vVertex = vVertices[inIndex]
		vVertex.position.x = inX
		vVertex.position.y = inY
		vVertex.color.r = inRed
		vVertex.color.g = inGreen
		vVertex.color.b = inBlue
		vVertex.color.a = inAlpha
	}

	vertex(0, x0, y0, r0, g0, b0, a0)
	vertex(1, x1, y1, r1, g1, b1, a1)
	vertex(2, x2, y2, r2, g2, b2, a2)
	SDL_RenderGeometry(handle, null, vVertices, 3, null, 0)
}

/** Fill a uniformly colored triangle (convenience overload). */
fun SDL3.Renderer.fillTriangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: SDL3.FColor) {
	fillTriangle(
		x0, y0, color.r, color.g, color.b, color.a,
		x1, y1, color.r, color.g, color.b, color.a,
		x2, y2, color.r, color.g, color.b, color.a,
	)
}
