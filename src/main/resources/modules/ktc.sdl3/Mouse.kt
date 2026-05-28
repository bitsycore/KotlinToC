@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

// Mouse button index constants
inline val SDL3.Mouse.Left   get() = C.SDL_BUTTON_LEFT
inline val SDL3.Mouse.Middle get() = C.SDL_BUTTON_MIDDLE
inline val SDL3.Mouse.Right  get() = C.SDL_BUTTON_RIGHT
inline val SDL3.Mouse.X1     get() = C.SDL_BUTTON_X1
inline val SDL3.Mouse.X2     get() = C.SDL_BUTTON_X2

// Mouse wheel direction constants
inline val SDL3.Mouse.WheelNormal  get() = C.SDL_MOUSEWHEEL_NORMAL
inline val SDL3.Mouse.WheelFlipped get() = C.SDL_MOUSEWHEEL_FLIPPED

/** Query current mouse position. Snapshot at call time. */
inline fun SDL3.Mouse.position(): SDL3.FPoint {
    var mx: Float = 0.0f
    var my: Float = 0.0f
    C.SDL_GetMouseState(C.addr(mx), C.addr(my))
    return SDL3.FPoint(mx, my)
}

/** True if the given mouse button is currently held down (polled, not event-based). */
inline fun SDL3.Mouse.isButtonDown(button: Int): Boolean {
    var mx: Float = 0.0f
    var my: Float = 0.0f
    val mask = C.SDL_GetMouseState(C.addr(mx), C.addr(my))
    return (mask and C.SDL_BUTTON_MASK(button)) != 0
}
