@file:cInclude("SDL3/SDL.h")
package sdl3

// Common keyboard scancodes as extension props on SDL3.Scancode.
// All are inlined at use site — zero runtime overhead.

inline val SDL3.Scancode.Escape get() = c.SDL_SCANCODE_ESCAPE
inline val SDL3.Scancode.Return get() = c.SDL_SCANCODE_RETURN
inline val SDL3.Scancode.Space  get() = c.SDL_SCANCODE_SPACE

// Arrow keys
inline val SDL3.Scancode.Left  get() = c.SDL_SCANCODE_LEFT
inline val SDL3.Scancode.Right get() = c.SDL_SCANCODE_RIGHT
inline val SDL3.Scancode.Up    get() = c.SDL_SCANCODE_UP
inline val SDL3.Scancode.Down  get() = c.SDL_SCANCODE_DOWN

// WASD
inline val SDL3.Scancode.W get() = c.SDL_SCANCODE_W
inline val SDL3.Scancode.A get() = c.SDL_SCANCODE_A
inline val SDL3.Scancode.S get() = c.SDL_SCANCODE_S
inline val SDL3.Scancode.D get() = c.SDL_SCANCODE_D
