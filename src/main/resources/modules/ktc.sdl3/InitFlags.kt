@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

// ==================
// MARK: InitFlags
// ==================

inline val SDL3.InitFlags.Audio    get() = C.SDL_INIT_AUDIO
inline val SDL3.InitFlags.Video    get() = C.SDL_INIT_VIDEO
inline val SDL3.InitFlags.Joystick get() = C.SDL_INIT_JOYSTICK
inline val SDL3.InitFlags.Haptic   get() = C.SDL_INIT_HAPTIC
inline val SDL3.InitFlags.Gamepad  get() = C.SDL_INIT_GAMEPAD
inline val SDL3.InitFlags.Events   get() = C.SDL_INIT_EVENTS
inline val SDL3.InitFlags.Sensor   get() = C.SDL_INIT_SENSOR
inline val SDL3.InitFlags.Camera   get() = C.SDL_INIT_CAMERA
