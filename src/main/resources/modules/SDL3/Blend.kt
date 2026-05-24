@file:cInclude("SDL3/SDL.h")

package sdl3

inline val SDL3.BlendMode.None  get() = c.SDL_BLENDMODE_NONE
inline val SDL3.BlendMode.Blend get() = c.SDL_BLENDMODE_BLEND
inline val SDL3.BlendMode.Add   get() = c.SDL_BLENDMODE_ADD
inline val SDL3.BlendMode.Mod   get() = c.SDL_BLENDMODE_MOD
inline val SDL3.BlendMode.Mul   get() = c.SDL_BLENDMODE_MUL
