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

    val background   = SDL3.Color(30,  30,  30,  255)
    val boxColor     = SDL3.Color(0,   128, 255, 255)
    val hoverColor   = SDL3.Color(255, 200, 0,   255)
    val outlineColor = SDL3.Color(255, 255, 255, 128)
    val crosshairCol = SDL3.Color(180, 180, 180, 200)

    var box     = SDL3.FRect(300.0f, 225.0f, 200.0f, 150.0f)
    var mouseX  = 0.0f
    var mouseY  = 0.0f
    var running = true

    // Held-key state — movement is continuous and delta-scaled
    var moveLeft  = false
    var moveRight = false
    var moveUp    = false
    var moveDown  = false

    val frameMs = 1000L / 60L   // target frame budget: ~16 ms
    val speed   = 200.0f        // pixels per second

    var lastTick = SDL3.ticks()

    while (running) {
        val now = SDL3.ticks()
        val dt  = (now - lastTick).toFloat() / 1000.0f
        lastTick = now

        handleEvent { event ->
            when (event.type) {
                SDL3.Event.Quit -> running = false

                SDL3.Event.KeyDown -> when (event.key.scancode) {
                    SDL3.Scancode.Escape                  -> running   = false
                    SDL3.Scancode.Left,  SDL3.Scancode.A -> moveLeft  = true
                    SDL3.Scancode.Right, SDL3.Scancode.D -> moveRight = true
                    SDL3.Scancode.Up,    SDL3.Scancode.W -> moveUp    = true
                    SDL3.Scancode.Down,  SDL3.Scancode.S -> moveDown  = true
                }

                SDL3.Event.KeyUp -> when (event.key.scancode) {
                    SDL3.Scancode.Left,  SDL3.Scancode.A -> moveLeft  = false
                    SDL3.Scancode.Right, SDL3.Scancode.D -> moveRight = false
                    SDL3.Scancode.Up,    SDL3.Scancode.W -> moveUp    = false
                    SDL3.Scancode.Down,  SDL3.Scancode.S -> moveDown  = false
                }

                SDL3.Event.MouseMotion -> {
                    mouseX = event.motion.x
                    mouseY = event.motion.y
                }

                SDL3.Event.MouseButtonDown -> {
                    val mx: Float = event.button.x
                    val my: Float = event.button.y
                    if (mx >= box.x && mx <= box.x + box.w &&
                        my >= box.y && my <= box.y + box.h) {
                        box = box.copy(x = mx - box.w / 2.0f, y = my - box.h / 2.0f)
                        println(box)
                    }
                }
            }
        }

        // Apply continuous movement, dt-scaled so speed is frame-rate independent
        var dx = 0.0f
        var dy = 0.0f
        if (moveLeft)  dx -= speed * dt
        if (moveRight) dx += speed * dt
        if (moveUp)    dy -= speed * dt
        if (moveDown)  dy += speed * dt
        if (dx != 0.0f || dy != 0.0f) box = box.copy(x = box.x + dx, y = box.y + dy)

        val hovering = mouseX >= box.x && mouseX <= box.x + box.w &&
                       mouseY >= box.y && mouseY <= box.y + box.h

        renderer.setDrawColor(background)
        renderer.clear()

        if (hovering) renderer.setDrawColor(hoverColor) else renderer.setDrawColor(boxColor)
        renderer.fillRect(box)

        renderer.setDrawColor(outlineColor)
        renderer.drawRect(box)

        renderer.setDrawColor(crosshairCol)
        renderer.drawLine(mouseX - 10.0f, mouseY, mouseX + 10.0f, mouseY)
        renderer.drawLine(mouseX, mouseY - 10.0f, mouseX, mouseY + 10.0f)

        renderer.present()

        // Sleep the remainder of the frame budget to hold ~60 FPS
        val elapsed = SDL3.ticks() - now
        if (elapsed < frameMs) SDL3.delay((frameMs - elapsed).toInt())

        if (testMode) running = false
    }
}
