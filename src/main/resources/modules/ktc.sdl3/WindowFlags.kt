@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

// ==================
// MARK: WindowFlags
// ==================

inline val SDL3.WindowFlags.Fullscreen   get() = c.SDL_WINDOW_FULLSCREEN
inline val SDL3.WindowFlags.Resizable    get() = c.SDL_WINDOW_RESIZABLE
inline val SDL3.WindowFlags.Borderless   get() = c.SDL_WINDOW_BORDERLESS
inline val SDL3.WindowFlags.Hidden       get() = c.SDL_WINDOW_HIDDEN
inline val SDL3.WindowFlags.Maximized    get() = c.SDL_WINDOW_MAXIMIZED
inline val SDL3.WindowFlags.Minimized    get() = c.SDL_WINDOW_MINIMIZED
inline val SDL3.WindowFlags.HighDPI      get() = c.SDL_WINDOW_HIGH_PIXEL_DENSITY
inline val SDL3.WindowFlags.AlwaysOnTop  get() = c.SDL_WINDOW_ALWAYS_ON_TOP
