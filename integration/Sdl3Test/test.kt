@file:cInclude("SDL3/SDL.h")
package test

import sdl3.*

inline fun handleEvent(block: (@Ptr c.SDL_Event) -> Unit) {
    var event: c.SDL_Event = c.init()
    while (c.SDL_PollEvent(c.addr(event))) {
        block(c.addr(event))
    }
}

fun main(args: Array<String>) {
    var testMode = false
    for (arg in args) { if (arg == "--test") testMode = true }

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
        handleEvent { event ->
            when(event.type) {
                SDL3.Event.Quit -> running = false
                SDL3.Event.MouseButtonDown -> {
                    val mx = event.button.x
                    val my = event.button.y
                    if (mx >= box.x && mx <= box.x + box.w &&
                        my >= box.y && my <= box.y + box.h) {
                        val newX = mx - box.w / 2.0f
                        val newY = my - box.h / 2.0f
                        box = box.copy(newX, newY)
                        println(box)
                    }
                }
            }
        }
        renderer.setDrawColor(background)
        renderer.clear()
        renderer.setDrawColor(boxColor)
        renderer.fillRect(box)
        renderer.present()
        if (testMode) running = false
    }
}
