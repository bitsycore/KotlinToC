@file:cInclude("SDL3/SDL.h")

package sdl3

// ==================
// MARK: Draw shapes
// ==================

/** Draw a circle outline using the midpoint circle algorithm. */
fun SDL3.Renderer.drawCircle(cx: Float, cy: Float, radius: Float) {
    var x = 0.0f
    var y = radius
    var d = 1.25f - radius
    while (x <= y) {
        c.SDL_RenderPoint(this.handle, cx + x, cy + y)
        c.SDL_RenderPoint(this.handle, cx - x, cy + y)
        c.SDL_RenderPoint(this.handle, cx + x, cy - y)
        c.SDL_RenderPoint(this.handle, cx - x, cy - y)
        c.SDL_RenderPoint(this.handle, cx + y, cy + x)
        c.SDL_RenderPoint(this.handle, cx - y, cy + x)
        c.SDL_RenderPoint(this.handle, cx + y, cy - x)
        c.SDL_RenderPoint(this.handle, cx - y, cy - x)
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
fun SDL3.Renderer.fillCircle(cx: Float, cy: Float, radius: Float) {
    var y = -radius
    while (y <= radius) {
        val halfW: Float = c.sqrtf(radius * radius - y * y)
        val lineRect = SDL3.FRect(cx - halfW, cy + y, halfW * 2.0f, 1.0f)
        c.SDL_RenderFillRect(this.handle, c.addr(lineRect.sdl))
        y += 1.0f
    }
}
