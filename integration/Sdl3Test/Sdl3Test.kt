@file:cInclude("SDL3/SDL.h")
package Sdl3Test

fun errorSdl(inStr: String) {
    c.SDL_Quit()
    error("$inStr: ${c.SDL_GetError()}")
}

fun main() {
    c.SDL_SetMainReady()
    if (c.SDL_Init(c.SDL_INIT_VIDEO) < 0) errorSdl("SDL_Init failed")

    val window: @Ptr c.SDL_Window = c.SDL_CreateWindow(
        "SDL3 Click Box Example",
        800,
        600,
        0
    )
    if (!window) errorSdl("Window creation failed")

    val renderer: @Ptr c.SDL_Renderer = c.SDL_CreateRenderer(window, c.NULL)
    if (!renderer) {
        c.SDL_DestroyWindow(window)
        errorSdl("Renderer creation failed")
    }

    var box: c.SDL_FRect = c.zeroed()
    box.x = 300.0f
    box.y = 225.0f
    box.w = 200.0f
    box.h = 150.0f

    var event: c.SDL_Event = c.zeroed()
    var running = true

    while (running) {
        while (c.SDL_PollEvent(c.addr(event))) {
            if (event.type == c.SDL_EVENT_QUIT) {
                running = false
            }
            if (event.type == c.SDL_EVENT_MOUSE_BUTTON_DOWN) {
                val mx = event.button.x
                val my = event.button.y
                if (mx >= box.x && mx <= box.x + box.w &&
                    my >= box.y && my <= box.y + box.h) {
                    box.x = mx - box.w / 2.0f
                    box.y = my - box.h / 2.0f
                }
            }
        }

        c.SDL_SetRenderDrawColor(renderer, 30, 30, 30, 255)
        c.SDL_RenderClear(renderer)
        c.SDL_SetRenderDrawColor(renderer, 0, 128, 255, 255)
        c.SDL_RenderFillRect(renderer, c.addr(box))
        c.SDL_RenderPresent(renderer)
        Time.sleepMs(16)
    }

    c.SDL_DestroyRenderer(renderer)
    c.SDL_DestroyWindow(window)
    c.SDL_Quit()
}
