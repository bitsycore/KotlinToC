@file:cInclude("SDL3/SDL.h")

package sdl3

/** Draw a debug text string at (x, y) using the current draw color. 8x8 monospace font. */
inline fun SDL3.Renderer.drawDebugText(x: Float, y: Float, text: String) {
	c.SDL_RenderDebugText(this.handle, x, y, text.ptr)
}

/** Character size in pixels for debug text (8x8 monospace). */
inline fun SDL3.Renderer.debugTextCharSize(): Int = c.SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE
