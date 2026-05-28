@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

inline val SDL3.BlendMode.None  get() = C.SDL_BLENDMODE_NONE
inline val SDL3.BlendMode.Blend get() = C.SDL_BLENDMODE_BLEND
inline val SDL3.BlendMode.Add   get() = C.SDL_BLENDMODE_ADD
inline val SDL3.BlendMode.Mod   get() = C.SDL_BLENDMODE_MOD
inline val SDL3.BlendMode.Mul   get() = C.SDL_BLENDMODE_MUL
