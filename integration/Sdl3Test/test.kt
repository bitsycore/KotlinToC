@file:cInclude("SDL3/SDL.h")
package test

import sdl3.*

inline fun handleEvent(block: (@Ptr c.SDL_Event) -> Unit) {
    var event: c.SDL_Event = c.init()
    while (c.SDL_PollEvent(c.addr(event))) {
        block(c.addr(event))
    }
}

fun main() {
    SDL3.initialize()
    defer SDL3.quit()

    val window = SDL3.Window("SDL3 Click Box Example", 800, 600)
    defer renderer.destroy()

    val renderer = SDL3.Renderer(window)
    defer window.destroy()

    var box = SDL3.FRect(300.0f, 225.0f, 200.0f, 150.0f)
    val background = SDL3.Color(30, 30, 30, 255)
    val boxColor = SDL3.Color(0, 128, 255, 255)
    var running = true
    while (running) {
        handleEvent() { event ->
            if (event.type == SDL3.Event.Quit) running = false
            if (event.type == SDL3.Event.MouseButtonDown) {
                val mx = event.button.x
                val my = event.button.y
                if (mx >= box.x && mx <= box.x + box.w &&
                    my >= box.y && my <= box.y + box.h) {
                    box.x = mx - box.w / 2.0f
                    box.y = my - box.h / 2.0f
                    println(box)
                }
            }
        }
        renderer.setDrawColor(background)
        renderer.clear()
        renderer.setDrawColor(boxColor)
        renderer.fillRect(box)
        renderer.present()
    }
}
