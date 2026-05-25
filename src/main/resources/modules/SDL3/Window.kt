@file:cInclude("SDL3/SDL.h")

package sdl3

inline fun SDL3.Window.destroy() { c.SDL_DestroyWindow(this.handle) }

inline fun SDL3.Window.setTitle(title: String) { c.SDL_SetWindowTitle(this.handle, title.ptr) }

inline fun SDL3.Window.setFullscreen(fullscreen: Boolean) {
    c.SDL_SetWindowFullscreen(this.handle, fullscreen)
}

fun SDL3.Window.getSize(): SDL3.FPoint {
    var w = 0
    var h = 0
    c.SDL_GetWindowSize(this.handle, c.addr(w), c.addr(h))
    return SDL3.FPoint(w.toFloat(), h.toFloat())
}

inline fun SDL3.Window.setResizable(resizable: Boolean) {
    c.SDL_SetWindowResizable(this.handle, resizable)
}

inline fun SDL3.Window.show()     { c.SDL_ShowWindow(this.handle) }
inline fun SDL3.Window.hide()     { c.SDL_HideWindow(this.handle) }
inline fun SDL3.Window.raise()    { c.SDL_RaiseWindow(this.handle) }
inline fun SDL3.Window.minimize() { c.SDL_MinimizeWindow(this.handle) }
inline fun SDL3.Window.maximize() { c.SDL_MaximizeWindow(this.handle) }
inline fun SDL3.Window.restore()  { c.SDL_RestoreWindow(this.handle) }

inline fun SDL3.Window.setPosition(x: Int, y: Int) {
    c.SDL_SetWindowPosition(this.handle, x, y)
}

fun SDL3.Window.getPosition(): SDL3.FPoint {
    var x = 0
    var y = 0
    c.SDL_GetWindowPosition(this.handle, c.addr(x), c.addr(y))
    return SDL3.FPoint(x.toFloat(), y.toFloat())
}

/** Flags bitmask for the window (SDL_WindowFlags). */
inline fun SDL3.Window.getFlags(): Int = c.SDL_GetWindowFlags(this.handle)

/** Set minimum window size for resizable windows. */
inline fun SDL3.Window.setMinimumSize(w: Int, h: Int) {
    c.SDL_SetWindowMinimumSize(this.handle, w, h)
}

/** Set maximum window size for resizable windows. */
inline fun SDL3.Window.setMaximumSize(w: Int, h: Int) {
    c.SDL_SetWindowMaximumSize(this.handle, w, h)
}

/** Center the window on its current display. */
inline fun SDL3.Window.centerOnScreen() {
    c.SDL_SetWindowPosition(this.handle, 0x2FFF0000, 0x2FFF0000)
}

/** Set the window border/decoration state. */
inline fun SDL3.Window.setBordered(bordered: Boolean) {
    c.SDL_SetWindowBordered(this.handle, bordered)
}

/** Set window always-on-top. */
inline fun SDL3.Window.setAlwaysOnTop(onTop: Boolean) {
    c.SDL_SetWindowAlwaysOnTop(this.handle, onTop)
}

/** Current pixel size of the window (after scaling / HiDPI). */
inline fun SDL3.Window.getSizeInPixels(): SDL3.FPoint {
    var w = 0
    var h = 0
    c.SDL_GetWindowSizeInPixels(this.handle, c.addr(w), c.addr(h))
    return SDL3.FPoint(w.toFloat(), h.toFloat())
}

/** Set window opacity (0.0 = transparent, 1.0 = opaque). */
inline fun SDL3.Window.setOpacity(opacity: Float) {
    c.SDL_SetWindowOpacity(this.handle, opacity)
}

/** Get current window opacity. */
inline fun SDL3.Window.getOpacity(): Float = c.SDL_GetWindowOpacity(this.handle)

/** Enable or disable relative mouse mode (hides cursor, reports relative motion). */
inline fun SDL3.Window.setRelativeMouseMode(enabled: Boolean) {
    c.SDL_SetWindowRelativeMouseMode(this.handle, enabled)
}

/** Warp the mouse pointer to a position within this window. */
inline fun SDL3.Window.warpMouse(x: Float, y: Float) {
    c.SDL_WarpMouseInWindow(this.handle, x, y)
}

/** Confine the mouse cursor to this window. */
inline fun SDL3.Window.setMouseGrab(grabbed: Boolean) {
    c.SDL_SetWindowMouseGrab(this.handle, grabbed)
}
