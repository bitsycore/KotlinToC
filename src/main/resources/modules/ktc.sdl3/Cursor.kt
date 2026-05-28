@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

// ==================
// MARK: SystemCursor
// ==================

inline val SDL3.SystemCursor.Default    get() = C.SDL_SYSTEM_CURSOR_DEFAULT
inline val SDL3.SystemCursor.Text       get() = C.SDL_SYSTEM_CURSOR_TEXT
inline val SDL3.SystemCursor.Wait       get() = C.SDL_SYSTEM_CURSOR_WAIT
inline val SDL3.SystemCursor.Crosshair  get() = C.SDL_SYSTEM_CURSOR_CROSSHAIR
inline val SDL3.SystemCursor.Progress   get() = C.SDL_SYSTEM_CURSOR_PROGRESS
inline val SDL3.SystemCursor.NwseResize get() = C.SDL_SYSTEM_CURSOR_NWSE_RESIZE
inline val SDL3.SystemCursor.NeswResize get() = C.SDL_SYSTEM_CURSOR_NESW_RESIZE
inline val SDL3.SystemCursor.EwResize   get() = C.SDL_SYSTEM_CURSOR_EW_RESIZE
inline val SDL3.SystemCursor.NsResize   get() = C.SDL_SYSTEM_CURSOR_NS_RESIZE
inline val SDL3.SystemCursor.Move       get() = C.SDL_SYSTEM_CURSOR_MOVE
inline val SDL3.SystemCursor.NotAllowed get() = C.SDL_SYSTEM_CURSOR_NOT_ALLOWED
inline val SDL3.SystemCursor.Pointer    get() = C.SDL_SYSTEM_CURSOR_POINTER

// ==================
// MARK: Cursor
// ==================

/** Create a system cursor from a SDL_SystemCursor id. Destroy with destroy() when done. */
inline fun SDL3.createSystemCursor(id: Int): SDL3.Cursor {
    val vHandle: Ref<C.SDL_Cursor> = C.SDL_CreateSystemCursor(id)
    if (!vHandle) error("SDL_CreateSystemCursor failed: ${C.SDL_GetError()}")
    return SDL3.Cursor(vHandle)
}

inline fun SDL3.Cursor.destroy() { C.SDL_DestroyCursor(this.handle) }

/** Set this cursor as the active cursor. */
inline fun SDL3.Cursor.activate() { C.SDL_SetCursor(this.handle) }
