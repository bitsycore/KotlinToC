@file:cInclude("SDL3/SDL.h")
package Sdl3Test

// Minimal SDL3 integration test.
// Uses only SDL_INIT_EVENTS — no video/audio subsystem, so it runs headless.
// SDL_MAIN_HANDLED is set via CMake (ktc_user.cmake) to prevent SDL from
// overriding main(); SDL_SetMainReady() informs SDL we manage main ourselves.
fun main() {
    c.SDL_SetMainReady()

    val ok: Int = c.SDL_Init(c.SDL_INIT_EVENTS)
    if (ok == 0) {
        error("SDL_Init failed: ${c.SDL_GetError()}")
    }

    val version = c.SDL_GetVersion()
    println("SDL3 ${version} initialized OK")

    c.SDL_Quit()
    println("SDL3 quit OK")
}
