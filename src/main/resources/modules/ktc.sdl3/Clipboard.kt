@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

/** Copy text to the system clipboard. */
inline fun SDL3.setClipboardText(text: String) {
	C.SDL_SetClipboardText(text.cPtr)
}

/** True if the clipboard currently contains text. */
inline fun SDL3.hasClipboardText(): Boolean = C.SDL_HasClipboardText()
