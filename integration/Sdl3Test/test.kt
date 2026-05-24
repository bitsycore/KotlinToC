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
    defer window.destroy()

    val renderer = SDL3.Renderer(window)
    defer renderer.destroy()

    // Colours
    val background  = SDL3.Color(30,  30,  30,  255)
    val boxColor    = SDL3.Color(0,   128, 255, 255)
    val hoverColor  = SDL3.Color(255, 200, 0,   255)
    val outlineColor= SDL3.Color(255, 255, 255, 128)
    val crosshair   = SDL3.Color(180, 180, 180, 200)

    var box     = SDL3.FRect(300.0f, 225.0f, 200.0f, 150.0f)
    var mouseX  = 0.0f
    var mouseY  = 0.0f
    var running = true

    while (running) {
        handleEvent { event ->
            when (event.type) {
                SDL3.Event.Quit -> running = false

                SDL3.Event.KeyDown -> {
                    val sc = event.key.scancode
                    when (sc) {
                        SDL3.Scancode.Escape -> running = false
                        SDL3.Scancode.Left,  SDL3.Scancode.A -> box = box.copy(x = box.x - 10.0f)
                        SDL3.Scancode.Right, SDL3.Scancode.D -> box = box.copy(x = box.x + 10.0f)
                        SDL3.Scancode.Up,    SDL3.Scancode.W -> box = box.copy(y = box.y - 10.0f)
                        SDL3.Scancode.Down,  SDL3.Scancode.S -> box = box.copy(y = box.y + 10.0f)
                    }
                }

                SDL3.Event.MouseMotion -> {
                    mouseX = event.motion.x
                    mouseY = event.motion.y
                }

                SDL3.Event.MouseButtonDown -> {
                    val mx = event.button.x
                    val my = event.button.y
                    if (mx >= box.x && mx <= box.x + box.w &&
                        my >= box.y && my <= box.y + box.h) {
                        box = box.copy(x = mx - box.w / 2.0f, y = my - box.h / 2.0f)
                        println(box)
                    }
                }
            }
        }

        val hovering = mouseX >= box.x && mouseX <= box.x + box.w &&
                       mouseY >= box.y && mouseY <= box.y + box.h

        // Draw background
        renderer.setDrawColor(background)
        renderer.clear()

        // Draw box (yellow when hovered, blue otherwise)
        if (hovering) renderer.setDrawColor(hoverColor) else renderer.setDrawColor(boxColor)
        renderer.fillRect(box)

        // Draw white outline around box
        renderer.setDrawColor(outlineColor)
        renderer.drawRect(box)

        // Draw crosshair at mouse position
        renderer.setDrawColor(crosshair)
        renderer.drawLine(mouseX - 10.0f, mouseY, mouseX + 10.0f, mouseY)
        renderer.drawLine(mouseX, mouseY - 10.0f, mouseX, mouseY + 10.0f)

        renderer.present()
        if (testMode) running = false
    }
}
