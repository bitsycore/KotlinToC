package Sdl3Test

// Minimal SDL3 integration test.
// Uses only SDL_INIT_EVENTS — no video/audio subsystem, so it runs headless.
// SDL_MAIN_HANDLED is set via CMake (ktc_user.cmake) to prevent SDL from
// overriding main(); SDL_SetMainReady() informs SDL we manage main ourselves.
fun main() {
    c.SDL_SetMainReady()

    val vOk: Int = c.SDL_Init(c.SDL_INIT_EVENTS)
    if (vOk == 0) {
        c.printf("SDL_Init failed: %s\n", c.SDL_GetError())
        c.exit(1)
    }

    val vVersion: Int = c.SDL_GetVersion()
    val vMajor: Int = vVersion / 1000000
    val vMinor: Int = (vVersion / 1000) % 1000
    val vPatch: Int = vVersion % 1000
    c.printf("SDL3 %d.%d.%d initialized OK\n", vMajor, vMinor, vPatch)

    c.SDL_Quit()
    c.printf("SDL3 quit OK\n")
}
