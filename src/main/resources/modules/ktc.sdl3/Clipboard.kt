@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

/** Copy text to the system clipboard. */
inline fun SDL3.setClipboardText(text: String) {
	c.SDL_SetClipboardText(text.ptr)
}

/** True if the clipboard currently contains text. */
inline fun SDL3.hasClipboardText(): Boolean = c.SDL_HasClipboardText()
