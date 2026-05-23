@file:cInclude("SDL3/SDL.h")
package Sdl3Test

import sdl3.*

/*
SDL3 click-box example using the sdl3 Kotlin wrapper.
Click inside the blue box to re-centre it on the mouse cursor.
*/

fun main() {
    SDL3.initialize()
    defer SDL3.quit()

    val window = SDL3.Window("SDL3 Click Box Example", 800, 600)
    defer renderer.destroy()

    val renderer = SDL3.Renderer(window)
    defer window.destroy()

    var box = SDL3.FRect(300.0f, 225.0f, 200.0f, 150.0f)

    var event: c.SDL_Event = c.zeroed()
    var running = true

    while (running) {
        while (c.SDL_PollEvent(c.addr(event))) {
            if (event.type == c.SDL_EVENT_QUIT) running = false
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

        renderer.setDrawColor(30, 30, 30, 255)
        renderer.clear()
        renderer.setDrawColor(0, 128, 255, 255)
        renderer.fillRect(box)
        renderer.present()
        Time.sleepMs(16)
    }
}
