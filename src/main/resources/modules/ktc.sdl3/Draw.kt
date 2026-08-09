@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

// ==================
// MARK: Draw shapes
// ==================

/** Draw a circle outline using the midpoint circle algorithm. */
inline fun SDL3.Renderer.drawCircle(cx: Float, cy: Float, radius: Float) {
	var x = 0.0f
	var y = radius
	var d = 1.25f - radius
	while (x <= y) {
		C.SDL_RenderPoint(this.handle, cx + x, cy + y)
		C.SDL_RenderPoint(this.handle, cx - x, cy + y)
		C.SDL_RenderPoint(this.handle, cx + x, cy - y)
		C.SDL_RenderPoint(this.handle, cx - x, cy - y)
		C.SDL_RenderPoint(this.handle, cx + y, cy + x)
		C.SDL_RenderPoint(this.handle, cx - y, cy + x)
		C.SDL_RenderPoint(this.handle, cx + y, cy - x)
		C.SDL_RenderPoint(this.handle, cx - y, cy - x)
		if (d < 0.0f) {
			d += 2.0f * x + 3.0f
		} else {
			d += 2.0f * (x - y) + 5.0f
			y -= 1.0f
		}
		x += 1.0f
	}
}

/** Fill a circle using horizontal scanlines. */
inline fun SDL3.Renderer.fillCircle(cx: Float, cy: Float, radius: Float) {
	var y = -radius
	while (y <= radius) {
		val halfW: Float = C.sqrtf(radius * radius - y * y)
		val lineRect = SDL3.FRect(cx - halfW, cy + y, halfW * 2.0f, 1.0f)
		C.SDL_RenderFillRect(this.handle, C.addr(lineRect.sdl))
		y += 1.0f
	}
}

// ==================
// MARK: Rounded rectangles
// ==================

/**
 * Fill a rectangle with rounded corners.
 * All four corners are filled in one pass: centre + side strips + corner scanlines.
 */
inline fun SDL3.Renderer.fillRoundedRect(rect: SDL3.FRect, radius: Float) {
	val r = if (radius > rect.w / 2.0f) rect.w / 2.0f else if (radius > rect.h / 2.0f) rect.h / 2.0f else radius
	// Centre column (full height, between the corner arcs)
	val midRect = SDL3.FRect(rect.x + r, rect.y, rect.w - r * 2.0f, rect.h)
	C.SDL_RenderFillRect(this.handle, C.addr(midRect.sdl))
	// Left and right strips (inner band, excluding the corner arcs)
	val leftRect  = SDL3.FRect(rect.x,              rect.y + r, r, rect.h - r * 2.0f)
	val rightRect = SDL3.FRect(rect.x + rect.w - r, rect.y + r, r, rect.h - r * 2.0f)
	C.SDL_RenderFillRect(this.handle, C.addr(leftRect.sdl))
	C.SDL_RenderFillRect(this.handle, C.addr(rightRect.sdl))
	// Corner arcs - scanline per row, all 4 corners in one loop
	var qy = 0.0f
	while (qy <= r) {
		val hw: Float = C.sqrtf(r * r - qy * qy)
		val tlr = SDL3.FRect(rect.x + r - hw,          rect.y + r - qy,          hw, 1.0f)
		val trr = SDL3.FRect(rect.x + rect.w - r,       rect.y + r - qy,          hw, 1.0f)
		val blr = SDL3.FRect(rect.x + r - hw,           rect.y + rect.h - r + qy, hw, 1.0f)
		val brr = SDL3.FRect(rect.x + rect.w - r,       rect.y + rect.h - r + qy, hw, 1.0f)
		C.SDL_RenderFillRect(this.handle, C.addr(tlr.sdl))
		C.SDL_RenderFillRect(this.handle, C.addr(trr.sdl))
		C.SDL_RenderFillRect(this.handle, C.addr(blr.sdl))
		C.SDL_RenderFillRect(this.handle, C.addr(brr.sdl))
		qy += 1.0f
	}
}

// ==================
// MARK: Triangles (SDL_RenderGeometry)
// ==================

/**
 * Fill a triangle with per-vertex RGBA float colors [0..1].
 * Uses SDL_RenderGeometry - blending must be set before calling if transparency is needed.
 */
inline fun SDL3.Renderer.fillTriangle(
    x0: Float, y0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
    x1: Float, y1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
    x2: Float, y2: Float, r2: Float, g2: Float, b2: Float, a2: Float
) {
    var v0: C.SDL_Vertex = C.init()
    var v1: C.SDL_Vertex = C.init()
    var v2: C.SDL_Vertex = C.init()
    v0.position.x = x0; v0.position.y = y0
    v0.color.r = r0; v0.color.g = g0; v0.color.b = b0; v0.color.a = a0
    v1.position.x = x1; v1.position.y = y1
    v1.color.r = r1; v1.color.g = g1; v1.color.b = b1; v1.color.a = a1
    v2.position.x = x2; v2.position.y = y2
    v2.color.r = r2; v2.color.g = g2; v2.color.b = b2; v2.color.a = a2
    val verts = arrayOf(v0, v1, v2)
    C.SDL_RenderGeometry(this.handle, C.NULL, verts.cPtr, 3, C.NULL, 0)
}

/** Fill a uniformly colored triangle (convenience overload). */
inline fun SDL3.Renderer.fillTriangle(
    x0: Float, y0: Float,
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    color: SDL3.FColor
) {
    fillTriangle(
        x0, y0, color.r, color.g, color.b, color.a,
        x1, y1, color.r, color.g, color.b, color.a,
        x2, y2, color.r, color.g, color.b, color.a
    )
}

// ==================
// MARK: Rounded rectangle outlines
// ==================

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
inline fun SDL3.Renderer.drawRoundedRect(rect: SDL3.FRect, radius: Float) {
	val r = if (radius > rect.w / 2.0f) rect.w / 2.0f else if (radius > rect.h / 2.0f) rect.h / 2.0f else radius
	// Straight edges as point loops - same rasterizer as the corner arcs below.
	val topY    = rect.y
	val bottomY = rect.y + rect.h
	val xStart  = rect.x + r
	val xEnd    = rect.x + rect.w - r
	var ex = xStart
	while (ex <= xEnd) {
		C.SDL_RenderPoint(this.handle, ex, topY)
		C.SDL_RenderPoint(this.handle, ex, bottomY)
		ex += 1.0f
	}
	val leftX  = rect.x
	val rightX = rect.x + rect.w
	val yStart = rect.y + r
	val yEnd   = rect.y + rect.h - r
	var ey = yStart
	while (ey <= yEnd) {
		C.SDL_RenderPoint(this.handle, leftX,  ey)
		C.SDL_RenderPoint(this.handle, rightX, ey)
		ey += 1.0f
	}
	// Corner arc centres
	val tlx = rect.x + r;            val tly = rect.y + r
	val trx = rect.x + rect.w - r;   val try_ = rect.y + r
	val blx = rect.x + r;            val bly = rect.y + rect.h - r
	val brx = rect.x + rect.w - r;   val bry = rect.y + rect.h - r
	// Midpoint circle - all 4 corners per step
	var px = 0.0f
	var py = r
	var d = 1.25f - r
	while (px <= py) {
		C.SDL_RenderPoint(this.handle, tlx - px, tly - py); C.SDL_RenderPoint(this.handle, tlx - py, tly - px)
		C.SDL_RenderPoint(this.handle, trx + px, try_ - py); C.SDL_RenderPoint(this.handle, trx + py, try_ - px)
		C.SDL_RenderPoint(this.handle, blx - px, bly + py); C.SDL_RenderPoint(this.handle, blx - py, bly + px)
		C.SDL_RenderPoint(this.handle, brx + px, bry + py); C.SDL_RenderPoint(this.handle, brx + py, bry + px)
		if (d < 0.0f) {
			d += 2.0f * px + 3.0f
		} else {
			d += 2.0f * (px - py) + 5.0f
			py -= 1.0f
		}
		px += 1.0f
	}
}
