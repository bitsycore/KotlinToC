@file:cInclude("SDL3/SDL.h")

package sdl3

inline fun SDL3.Renderer.destroy() { c.SDL_DestroyRenderer(this.handle) }

/** Set the draw colour for subsequent rendering calls. */
inline fun SDL3.Renderer.setDrawColor(r: Int, g: Int, b: Int, a: Int) {
    c.SDL_SetRenderDrawColor(this.handle, r, g, b, a)
}

inline fun SDL3.Renderer.setDrawColor(color: SDL3.Color) {
    c.SDL_SetRenderDrawColor(this.handle, color.r, color.g, color.b, color.a)
}

/** Clear the render target with the current draw colour. */
inline fun SDL3.Renderer.clear() { c.SDL_RenderClear(this.handle) }

/** Fill a rectangle on the render target. */
inline fun SDL3.Renderer.fillRect(inRect: SDL3.FRect) {
    c.SDL_RenderFillRect(this.handle, c.addr(inRect.sdl))
}

/** Draw the outline of a rectangle on the render target. */
inline fun SDL3.Renderer.drawRect(inRect: SDL3.FRect) {
    c.SDL_RenderRect(this.handle, c.addr(inRect.sdl))
}

/** Draw a line segment on the render target. */
inline fun SDL3.Renderer.drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
    c.SDL_RenderLine(this.handle, x1, y1, x2, y2)
}

/** Draw a single point on the render target. */
inline fun SDL3.Renderer.drawPoint(x: Float, y: Float) {
    c.SDL_RenderPoint(this.handle, x, y)
}

inline fun SDL3.Renderer.drawPoint(point: SDL3.FPoint) {
    c.SDL_RenderPoint(this.handle, point.x, point.y)
}

/** Set blend mode for subsequent draw calls. */
inline fun SDL3.Renderer.setBlendMode(mode: Int) {
    c.SDL_SetRenderDrawBlendMode(this.handle, mode)
}

/** Set the drawing scale (zoom) factor. */
inline fun SDL3.Renderer.setScale(scaleX: Float, scaleY: Float) {
    c.SDL_SetRenderScale(this.handle, scaleX, scaleY)
}

/** Present the rendered frame to the screen. */
inline fun SDL3.Renderer.present() { c.SDL_RenderPresent(this.handle) }

/** Pixel dimensions of the render output (window or render target). */
inline fun SDL3.Renderer.outputSize(): SDL3.FPoint {
    var w = 0
    var h = 0
    c.SDL_GetRenderOutputSize(this.handle, c.addr(w), c.addr(h))
    return SDL3.FPoint(w.toFloat(), h.toFloat())
}

/** Restrict rendering to a sub-rectangle of the target (integer coords). */
inline fun SDL3.Renderer.setViewport(rect: SDL3.Rect) {
    c.SDL_SetRenderViewport(this.handle, c.addr(rect.sdl))
}

inline fun SDL3.Renderer.clearViewport() {
    c.SDL_SetRenderViewport(this.handle, c.NULL)
}

/** Restrict rendering to a clip rectangle. */
inline fun SDL3.Renderer.setClipRect(rect: SDL3.Rect) {
    c.SDL_SetRenderClipRect(this.handle, c.addr(rect.sdl))
}

inline fun SDL3.Renderer.clearClipRect() {
    c.SDL_SetRenderClipRect(this.handle, c.NULL)
}

/** Set the logical presentation size for scaling. */
inline fun SDL3.Renderer.setLogicalSize(w: Int, h: Int) {
    c.SDL_SetRenderLogicalPresentation(this.handle, w, h, c.SDL_LOGICAL_PRESENTATION_LETTERBOX)
}

/** Reset logical presentation back to native window resolution. */
inline fun SDL3.Renderer.clearLogicalSize() {
    c.SDL_SetRenderLogicalPresentation(this.handle, 0, 0, c.SDL_LOGICAL_PRESENTATION_DISABLED)
}
