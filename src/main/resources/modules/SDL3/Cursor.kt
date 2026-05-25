@file:cInclude("SDL3/SDL.h")

package sdl3

// ==================
// MARK: SystemCursor
// ==================

inline val SDL3.SystemCursor.Default    get() = c.SDL_SYSTEM_CURSOR_DEFAULT
inline val SDL3.SystemCursor.Text       get() = c.SDL_SYSTEM_CURSOR_TEXT
inline val SDL3.SystemCursor.Wait       get() = c.SDL_SYSTEM_CURSOR_WAIT
inline val SDL3.SystemCursor.Crosshair  get() = c.SDL_SYSTEM_CURSOR_CROSSHAIR
inline val SDL3.SystemCursor.Progress   get() = c.SDL_SYSTEM_CURSOR_PROGRESS
inline val SDL3.SystemCursor.NwseResize get() = c.SDL_SYSTEM_CURSOR_NWSE_RESIZE
inline val SDL3.SystemCursor.NeswResize get() = c.SDL_SYSTEM_CURSOR_NESW_RESIZE
inline val SDL3.SystemCursor.EwResize   get() = c.SDL_SYSTEM_CURSOR_EW_RESIZE
inline val SDL3.SystemCursor.NsResize   get() = c.SDL_SYSTEM_CURSOR_NS_RESIZE
inline val SDL3.SystemCursor.Move       get() = c.SDL_SYSTEM_CURSOR_MOVE
inline val SDL3.SystemCursor.NotAllowed get() = c.SDL_SYSTEM_CURSOR_NOT_ALLOWED
inline val SDL3.SystemCursor.Pointer    get() = c.SDL_SYSTEM_CURSOR_POINTER

// ==================
// MARK: Cursor
// ==================

/** Create a system cursor from a SDL_SystemCursor id. Destroy with destroy() when done. */
inline fun SDL3.createSystemCursor(id: Int): SDL3.Cursor {
    val vHandle: @Ptr c.SDL_Cursor = c.SDL_CreateSystemCursor(id)
    if (!vHandle) error("SDL_CreateSystemCursor failed: ${c.SDL_GetError()}")
    return SDL3.Cursor(vHandle)
}

inline fun SDL3.Cursor.destroy() { c.SDL_DestroyCursor(this.handle) }

/** Set this cursor as the active cursor. */
inline fun SDL3.Cursor.activate() { c.SDL_SetCursor(this.handle) }
