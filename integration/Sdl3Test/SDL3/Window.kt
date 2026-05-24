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
