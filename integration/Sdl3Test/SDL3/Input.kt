@file:cInclude("SDL3/SDL.h")

package sdl3

// ==================
// Event polling

/** Drain SDL's event queue, calling block for each event. Inline — block expands at call site. */
inline fun pollEvents(block: (@Ptr c.SDL_Event) -> Unit) {
    var event: c.SDL_Event = c.init()
    while (c.SDL_PollEvent(c.addr(event))) {
        block(c.addr(event))
    }
}

// ==================
// Keyboard state

/** True if the key with the given scancode is currently held down (polled, not event-based). */
fun isKeyDown(scancode: Int): Boolean {
    var numKeys: Int = 0
    val state: @Ptr Int = c.SDL_GetKeyboardState(c.addr(numKeys))
    if (scancode < 0 || scancode >= numKeys) return false
    return state[scancode] != 0
}
