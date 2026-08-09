@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

// ==================
// Event polling

/** Drain SDL's event queue, calling block for each event. Inline - block expands at call site. */
inline fun pollEvents(block: (Ref<C.SDL_Event>) -> Unit) {
    var event: C.SDL_Event = C.init()
    while (C.SDL_PollEvent(C.addr(event))) {
        block(C.addr(event))
    }
}

// ==================
// Keyboard state

/** True if the key with the given scancode is currently held down (polled, not event-based). */
fun isKeyDown(scancode: Int): Boolean {
    var numKeys: Int = 0
    val state: Ref<Byte> = C.SDL_GetKeyboardState(C.addr(numKeys))
    if (scancode < 0 || scancode >= numKeys) return false
    return state[scancode] != 0.toByte()
}
