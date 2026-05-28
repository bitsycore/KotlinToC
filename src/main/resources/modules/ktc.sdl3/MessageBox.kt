@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

inline val SDL3.MessageBoxFlags.Error       get() = C.SDL_MESSAGEBOX_ERROR
inline val SDL3.MessageBoxFlags.Warning     get() = C.SDL_MESSAGEBOX_WARNING
inline val SDL3.MessageBoxFlags.Information get() = C.SDL_MESSAGEBOX_INFORMATION
