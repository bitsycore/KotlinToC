@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

// ==================
// MARK: InitFlags
// ==================

inline val SDL3.InitFlags.Audio    get() = c.SDL_INIT_AUDIO
inline val SDL3.InitFlags.Video    get() = c.SDL_INIT_VIDEO
inline val SDL3.InitFlags.Joystick get() = c.SDL_INIT_JOYSTICK
inline val SDL3.InitFlags.Haptic   get() = c.SDL_INIT_HAPTIC
inline val SDL3.InitFlags.Gamepad  get() = c.SDL_INIT_GAMEPAD
inline val SDL3.InitFlags.Events   get() = c.SDL_INIT_EVENTS
inline val SDL3.InitFlags.Sensor   get() = c.SDL_INIT_SENSOR
inline val SDL3.InitFlags.Camera   get() = c.SDL_INIT_CAMERA
