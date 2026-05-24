package test

import sdl3.*

fun main(args: Array<String>) {
    var testMode = false
    for (arg in args) { if (arg == "--skip-gui") testMode = true }

    SDL3.initialize()
    defer SDL3.quit()

    val window   = SDL3.Window("SDL3 Click Box Example", 800, 600, SDL3.WindowFlags.Resizable)
    defer window.destroy()

    val renderer = SDL3.Renderer(window)
    defer renderer.destroy()

    // Build a 64×64 sprite as a render-target texture (orange frame with yellow fill)
    val sprite = renderer.createTexture(64, 64, SDL3.PixelFormat.RGBA8888, SDL3.TextureAccess.Target)
    defer sprite.destroy()
    renderer.setTarget(sprite)
    renderer.setDrawColor(255, 80, 0, 255)
    renderer.clear()
    renderer.setDrawColor(255, 220, 0, 255)
    renderer.fillRect(SDL3.FRect(8.0f, 8.0f, 48.0f, 48.0f))
    renderer.clearTarget()

    val background   = SDL3.Color(30,  30,  30,  255)
    val boxColor     = SDL3.Color(0,   128, 255, 255)
    val hoverColor   = SDL3.Color(255, 200, 0,   255)
    val outlineColor = SDL3.Color(255, 255, 255, 128)
    val crosshairCol = SDL3.Color(180, 180, 180, 200)

    var box      = SDL3.FRect(300.0f, 225.0f, 200.0f, 150.0f)
    var mouseX   = 0.0f
    var mouseY   = 0.0f
    var spriteAngle = 0.0f
    var quit     = false

    // Held-key state
    var moveLeft  = false
    var moveRight = false
    var moveUp    = false
    var moveDown  = false

    val speed = 200.0f   // pixels per second

    gameLoop(60) { dt ->
        pollEvents { event ->
            when (event.type) {
                SDL3.Event.Quit -> quit = true

                SDL3.Event.KeyDown -> when (event.key.scancode) {
                    SDL3.Scancode.Escape                  -> quit      = true
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

        // Delta-scaled movement
        var dx = 0.0f
        var dy = 0.0f
        if (moveLeft)  dx -= speed * dt
        if (moveRight) dx += speed * dt
        if (moveUp)    dy -= speed * dt
        if (moveDown)  dy += speed * dt
        if (dx != 0.0f || dy != 0.0f) box = box.copy(x = box.x + dx, y = box.y + dy)

        val hovering = box.contains(SDL3.FPoint(mouseX, mouseY))

        renderer.setDrawColor(background)
        renderer.clear()

        if (hovering) renderer.setDrawColor(hoverColor) else renderer.setDrawColor(boxColor)
        renderer.fillRect(box)

        renderer.setDrawColor(outlineColor)
        renderer.drawRect(box)

        renderer.setDrawColor(crosshairCol)
        renderer.drawLine(mouseX - 10.0f, mouseY, mouseX + 10.0f, mouseY)
        renderer.drawLine(mouseX, mouseY - 10.0f, mouseX, mouseY + 10.0f)

        // Rotating sprite centered on the cursor
        spriteAngle += 90.0f * dt
        val spriteDst = SDL3.FRect(mouseX - 32.0f, mouseY - 32.0f, 64.0f, 64.0f)
        renderer.renderTextureRotated(sprite, spriteDst, spriteAngle, SDL3.Flip.None)

        renderer.present()

        !quit && !testMode
    }
}
