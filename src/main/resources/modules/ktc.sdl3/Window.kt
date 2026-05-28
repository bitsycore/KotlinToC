@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

inline fun SDL3.Window.destroy() { C.SDL_DestroyWindow(this.handle) }

inline fun SDL3.Window.setTitle(title: String) { C.SDL_SetWindowTitle(this.handle, title.ptr) }

inline fun SDL3.Window.setFullscreen(fullscreen: Boolean) {
    C.SDL_SetWindowFullscreen(this.handle, fullscreen)
}

fun SDL3.Window.getSize(): SDL3.FPoint {
    var w = 0
    var h = 0
    C.SDL_GetWindowSize(this.handle, C.addr(w), C.addr(h))
    return SDL3.FPoint(w.toFloat(), h.toFloat())
}

inline fun SDL3.Window.setResizable(resizable: Boolean) {
    C.SDL_SetWindowResizable(this.handle, resizable)
}

inline fun SDL3.Window.show()     { C.SDL_ShowWindow(this.handle) }
inline fun SDL3.Window.hide()     { C.SDL_HideWindow(this.handle) }
inline fun SDL3.Window.raise()    { C.SDL_RaiseWindow(this.handle) }
inline fun SDL3.Window.minimize() { C.SDL_MinimizeWindow(this.handle) }
inline fun SDL3.Window.maximize() { C.SDL_MaximizeWindow(this.handle) }
inline fun SDL3.Window.restore()  { C.SDL_RestoreWindow(this.handle) }

inline fun SDL3.Window.setPosition(x: Int, y: Int) {
    C.SDL_SetWindowPosition(this.handle, x, y)
}

fun SDL3.Window.getPosition(): SDL3.FPoint {
    var x = 0
    var y = 0
    C.SDL_GetWindowPosition(this.handle, C.addr(x), C.addr(y))
    return SDL3.FPoint(x.toFloat(), y.toFloat())
}

/** Flags bitmask for the window (SDL_WindowFlags). */
inline fun SDL3.Window.getFlags(): Int = C.SDL_GetWindowFlags(this.handle)

/** Set minimum window size for resizable windows. */
inline fun SDL3.Window.setMinimumSize(w: Int, h: Int) {
    C.SDL_SetWindowMinimumSize(this.handle, w, h)
}

/** Set maximum window size for resizable windows. */
inline fun SDL3.Window.setMaximumSize(w: Int, h: Int) {
    C.SDL_SetWindowMaximumSize(this.handle, w, h)
}

/** Center the window on its current display. */
inline fun SDL3.Window.centerOnScreen() {
    C.SDL_SetWindowPosition(this.handle, 0x2FFF0000, 0x2FFF0000)
}

/** Set the window border/decoration state. */
inline fun SDL3.Window.setBordered(bordered: Boolean) {
    C.SDL_SetWindowBordered(this.handle, bordered)
}

/** Set window always-on-top. */
inline fun SDL3.Window.setAlwaysOnTop(onTop: Boolean) {
    C.SDL_SetWindowAlwaysOnTop(this.handle, onTop)
}

/** Current pixel size of the window (after scaling / HiDPI). */
inline fun SDL3.Window.getSizeInPixels(): SDL3.FPoint {
    var w = 0
    var h = 0
    C.SDL_GetWindowSizeInPixels(this.handle, C.addr(w), C.addr(h))
    return SDL3.FPoint(w.toFloat(), h.toFloat())
}

/** Set window opacity (0.0 = transparent, 1.0 = opaque). */
inline fun SDL3.Window.setOpacity(opacity: Float) {
    C.SDL_SetWindowOpacity(this.handle, opacity)
}

/** Get current window opacity. */
inline fun SDL3.Window.getOpacity(): Float = C.SDL_GetWindowOpacity(this.handle)

/** Enable or disable relative mouse mode (hides cursor, reports relative motion). */
inline fun SDL3.Window.setRelativeMouseMode(enabled: Boolean) {
    C.SDL_SetWindowRelativeMouseMode(this.handle, enabled)
}

/** Warp the mouse pointer to a position within this window. */
inline fun SDL3.Window.warpMouse(x: Float, y: Float) {
    C.SDL_WarpMouseInWindow(this.handle, x, y)
}

/** Confine the mouse cursor to this window. */
inline fun SDL3.Window.setMouseGrab(grabbed: Boolean) {
    C.SDL_SetWindowMouseGrab(this.handle, grabbed)
}
