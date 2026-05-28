@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

inline fun SDL3.Renderer.destroy() { C.SDL_DestroyRenderer(this.handle) }

/** Set the draw colour for subsequent rendering calls. */
inline fun SDL3.Renderer.setDrawColor(r: Int, g: Int, b: Int, a: Int) {
    C.SDL_SetRenderDrawColor(this.handle, r, g, b, a)
}

inline fun SDL3.Renderer.setDrawColor(color: SDL3.Color) {
    C.SDL_SetRenderDrawColor(this.handle, color.r, color.g, color.b, color.a)
}

/** Clear the render target with the current draw colour. */
inline fun SDL3.Renderer.clear() { C.SDL_RenderClear(this.handle) }

/** Fill a rectangle on the render target. */
inline fun SDL3.Renderer.fillRect(inRect: SDL3.FRect) {
    C.SDL_RenderFillRect(this.handle, C.addr(inRect.sdl))
}

/** Draw the outline of a rectangle on the render target. */
inline fun SDL3.Renderer.drawRect(inRect: SDL3.FRect) {
    C.SDL_RenderRect(this.handle, C.addr(inRect.sdl))
}

/** Draw a line segment on the render target. */
inline fun SDL3.Renderer.drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
    C.SDL_RenderLine(this.handle, x1, y1, x2, y2)
}

/** Draw a single point on the render target. */
inline fun SDL3.Renderer.drawPoint(x: Float, y: Float) {
    C.SDL_RenderPoint(this.handle, x, y)
}

inline fun SDL3.Renderer.drawPoint(point: SDL3.FPoint) {
    C.SDL_RenderPoint(this.handle, point.x, point.y)
}

/** Set blend mode for subsequent draw calls. */
inline fun SDL3.Renderer.setBlendMode(mode: Int) {
    C.SDL_SetRenderDrawBlendMode(this.handle, mode)
}

/** Set the drawing scale (zoom) factor. */
inline fun SDL3.Renderer.setScale(scaleX: Float, scaleY: Float) {
    C.SDL_SetRenderScale(this.handle, scaleX, scaleY)
}

/** Present the rendered frame to the screen. */
inline fun SDL3.Renderer.present() { C.SDL_RenderPresent(this.handle) }

/** Pixel dimensions of the render output (window or render target). */
inline fun SDL3.Renderer.outputSize(): SDL3.FPoint {
    var w = 0
    var h = 0
    C.SDL_GetRenderOutputSize(this.handle, C.addr(w), C.addr(h))
    return SDL3.FPoint(w.toFloat(), h.toFloat())
}

/** Restrict rendering to a sub-rectangle of the target (integer coords). */
inline fun SDL3.Renderer.setViewport(rect: SDL3.Rect) {
    C.SDL_SetRenderViewport(this.handle, C.addr(rect.sdl))
}

inline fun SDL3.Renderer.clearViewport() {
    C.SDL_SetRenderViewport(this.handle, C.NULL)
}

/** Restrict rendering to a clip rectangle. */
inline fun SDL3.Renderer.setClipRect(rect: SDL3.Rect) {
    C.SDL_SetRenderClipRect(this.handle, C.addr(rect.sdl))
}

inline fun SDL3.Renderer.clearClipRect() {
    C.SDL_SetRenderClipRect(this.handle, C.NULL)
}

/** Set the logical presentation size for scaling. */
inline fun SDL3.Renderer.setLogicalSize(w: Int, h: Int) {
    C.SDL_SetRenderLogicalPresentation(this.handle, w, h, C.SDL_LOGICAL_PRESENTATION_LETTERBOX)
}

/** Reset logical presentation back to native window resolution. */
inline fun SDL3.Renderer.clearLogicalSize() {
    C.SDL_SetRenderLogicalPresentation(this.handle, 0, 0, C.SDL_LOGICAL_PRESENTATION_DISABLED)
}

/** Enable or disable VSync (0 = off, 1 = on, -1 = adaptive). */
inline fun SDL3.Renderer.setVSync(vsync: Int) {
    C.SDL_SetRenderVSync(this.handle, vsync)
}

/** Query current VSync setting. */
inline fun SDL3.Renderer.getVSync(): Int {
    var vsync = 0
    C.SDL_GetRenderVSync(this.handle, C.addr(vsync))
    return vsync
}
