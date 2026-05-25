package test

import sdl3.*

fun main(args: Array<String>) {
	var testMode = false
	for (arg in args) {
		if (arg == "--skip-gui") testMode = true
	}

	SDL3.initialize()
	defer { SDL3.quit() }

	val window = SDL3.Window("SDL3 Demo", 800, 600, SDL3.WindowFlags.Resizable or SDL3.WindowFlags.AlwaysOnTop)
	defer { window.destroy() }
	window.setMinimumSize(400, 300)
	window.centerOnScreen()
	window.setMaximumSize(1600, 1200)

	val renderer = SDL3.Renderer(window)
	defer { renderer.destroy() }

	// Custom cursors: pointer (hand) when hovering, default otherwise
	val cursorDefault = SDL3.createSystemCursor(SDL3.SystemCursor.Default)
	defer { cursorDefault.destroy() }
	val cursorPointer = SDL3.createSystemCursor(SDL3.SystemCursor.Pointer)
	defer { cursorPointer.destroy() }

	val sprite = createSpriteTexture(renderer)
	defer { sprite.destroy() }
	val atlas = createAtlasTexture(renderer)
	defer { atlas.destroy() }

	// Palette
	val background   = SDL3.Color(30, 30, 30, 255)
	val boxColor     = SDL3.Color(0, 128, 255, 255)
	val hoverColor   = SDL3.Color(255, 200, 0, 255)
	val outlineColor = SDL3.Color(255, 255, 255, 128)
	val crosshairCol = SDL3.Color(180, 180, 180, 200)
	val leashColor   = SDL3.Color(100, 100, 220, 120)

	var box        = SDL3.FRect(300.0f, 225.0f, 200.0f, 150.0f)
	var mouseX     = 0.0f
	var mouseY     = 0.0f
	var hoverPulse = 0.0f
	var quit       = false
	var wasHovering = false
	var fullscreen  = false
	var logicalMode = false

	// FPS tracking
	var frameCount = 0
	var titleTimer = 0.0f

	val movState    = MovementState()
	val speed       = 200.0f
	val pulse       = PulseState()
	val spriteState = SpriteState()

	// Sprite position + angle trail (circular buffer of 24 past states)
	val trailX     = FloatArray(24)
	val trailY     = FloatArray(24)
	val trailAngle = FloatArray(24)
	var trailHead  = 0

	// Atlas tile cycling
	var atlasTileTimer = 0.0f
	var atlasTileIdx   = 0

	gameLoop(60) { dt ->

		// ── FPS title update (once per second) ──────────────────────
		frameCount++
		titleTimer += dt
		if (titleTimer >= 1.0f) {
			val fps   = (frameCount.toFloat() / titleTimer).toInt()
			val wsize = window.getSize()
			window.setTitle("SDL3 Demo | FPS: $fps | ${wsize.x.toInt()}x${wsize.y.toInt()} | WASD RClick F=full L=lbox Z=zoom Spc=spin")
			titleTimer = 0.0f
			frameCount = 0
		}

		// ── Events ──────────────────────────────────────────────────
		pollEvents { event ->
			when (event.type) {
				SDL3.Event.Quit -> quit = true

				SDL3.Event.KeyDown -> when (event.key.scancode) {
					SDL3.Scancode.Escape -> quit = true
					SDL3.Scancode.Left,  SDL3.Scancode.A -> movState.left  = true
					SDL3.Scancode.Right, SDL3.Scancode.D -> movState.right = true
					SDL3.Scancode.Up,    SDL3.Scancode.W -> movState.up    = true
					SDL3.Scancode.Down,  SDL3.Scancode.S -> movState.down  = true
					SDL3.Scancode.F -> {
						fullscreen = !fullscreen
						window.setFullscreen(fullscreen)
					}
					SDL3.Scancode.L -> {
						logicalMode = !logicalMode
						if (logicalMode) renderer.setLogicalSize(800, 600) else renderer.clearLogicalSize()
					}
				}

				SDL3.Event.KeyUp -> when (event.key.scancode) {
					SDL3.Scancode.Left,  SDL3.Scancode.A -> movState.left  = false
					SDL3.Scancode.Right, SDL3.Scancode.D -> movState.right = false
					SDL3.Scancode.Up,    SDL3.Scancode.W -> movState.up    = false
					SDL3.Scancode.Down,  SDL3.Scancode.S -> movState.down  = false
				}

				SDL3.Event.MouseMotion -> {
					mouseX = event.motion.x
					mouseY = event.motion.y
				}

				SDL3.Event.MouseButtonDown -> {
					val mx: Float = event.button.x
					val my: Float = event.button.y
					val btn: Int  = event.button.button
					when (btn) {
						SDL3.Mouse.Left -> {
							if (box.contains(SDL3.FPoint(mx, my))) {
								box = box.copy(x = mx - box.w / 2.0f, y = my - box.h / 2.0f)
								println(box)
							}
						}
						SDL3.Mouse.Right -> pulse.spawn(mx, my)
					}
				}

				SDL3.Event.MouseWheel -> {
					val wy: Float = event.wheel.y
					val scale     = if (wy > 0.0f) 1.15f else 0.87f
					val newW      = box.w * scale
					val cw        = if (newW < 40.0f) 40.0f else if (newW > 500.0f) 500.0f else newW
					val newH      = box.h * scale
					val ch        = if (newH < 40.0f) 40.0f else if (newH > 500.0f) 500.0f else newH
					val cx        = box.x + box.w / 2.0f
					val cy        = box.y + box.h / 2.0f
					box           = SDL3.FRect(cx - cw / 2.0f, cy - ch / 2.0f, cw, ch)
				}
			}
		}

		// ── WASD box movement ────────────────────────────────────────
		val ws = renderer.outputSize()
		var dx = 0.0f
		var dy = 0.0f
		when {
			movState.left  -> dx -= speed * dt
			movState.right -> dx += speed * dt
			movState.up    -> dy -= speed * dt
			movState.down  -> dy += speed * dt
		}
		if (dx != 0.0f || dy != 0.0f) {
			val nx = box.x + dx
			val ny = box.y + dy
			val bx = if (nx < 0.0f) 0.0f else if (nx + box.w > ws.x) ws.x - box.w else nx
			val by = if (ny < 0.0f) 0.0f else if (ny + box.h > ws.y) ws.y - box.h else ny
			box = box.copy(x = bx, y = by)
		}

		// Held right-button: continuously spawn pulses
		if (SDL3.Mouse.isButtonDown(SDL3.Mouse.Right)) pulse.spawn(mouseX, mouseY)

		val hovering = box.contains(SDL3.FPoint(mouseX, mouseY))

		// Swap cursor on hover transition
		if (hovering != wasHovering) {
			if (hovering) cursorPointer.activate() else cursorDefault.activate()
			wasHovering = hovering
		}

		// ── Update state ─────────────────────────────────────────────
		pulse.update(dt)
		spriteState.update(dt, mouseX, mouseY)

		// Record trail before spinning (captures pre-spin angle)
		trailX[trailHead]     = spriteState.posX
		trailY[trailHead]     = spriteState.posY
		trailAngle[trailHead] = spriteState.angle
		trailHead = (trailHead + 1) % 24

		val spinSpeed = if (isKeyDown(SDL3.Scancode.Space)) 270.0f else 90.0f
		spriteState.spin(dt, spinSpeed)

		// Atlas tile cycles every 0.5 s
		atlasTileTimer += dt
		if (atlasTileTimer >= 0.5f) {
			atlasTileTimer -= 0.5f
			atlasTileIdx = (atlasTileIdx + 1) % 4
		}

		// Hover-ring pulse
		if (hovering) {
			hoverPulse += dt * 0.6f
			if (hoverPulse > 1.0f) hoverPulse = 0.0f
		} else {
			hoverPulse = 0.0f
		}

		// ── Render ───────────────────────────────────────────────────
		// Z key: 2× zoom applied to all main-scene content (setScale demo)
		val renderScale = if (isKeyDown(SDL3.Scancode.Z)) 2.0f else 1.0f
		renderer.setScale(renderScale, renderScale)
		renderer.setDrawColor(background)
		renderer.clear()

		pulse.render(renderer)
		renderer.renderBox(box, hovering, hoverPulse, boxColor, hoverColor, outlineColor)
		renderer.renderLeash(box, mouseX, mouseY, movState, leashColor)
		renderer.renderDirectionArrow(box, mouseX, mouseY)
		renderer.renderCrosshair(mouseX, mouseY, crosshairCol)

		// Trail line: shows lag between sprite position and cursor
		renderer.setDrawColor(SDL3.Color(255, 255, 255, 60))
		renderer.drawLine(spriteState.posX, spriteState.posY, mouseX, mouseY)

		// Ghost sprite trail: fading rotated echoes of past positions
		sprite.setColorMod(255, 255, 255)
		for (i in 0 until 24) {
			val tidx   = (trailHead + i) % 24
			val talpha = (i + 1) * 10
			sprite.setAlphaMod(talpha)
			val ghostDst = SDL3.FRect(trailX[tidx] - 16.0f, trailY[tidx] - 16.0f, 32.0f, 32.0f)
			renderer.renderTextureRotated(sprite, ghostDst, trailAngle[tidx], SDL3.Flip.None)
		}
		spriteState.applyMods(sprite)
		spriteState.render(renderer, sprite)

		// Reset scale for HUD (atlas + minimap are always 1:1)
		renderer.setScale(1.0f, 1.0f)
		renderer.renderAtlasHud(atlas, atlasTileIdx, ws)
		renderer.renderMinimap(ws, box, spriteState.posX, spriteState.posY, mouseX, mouseY, hovering, boxColor, hoverColor)

		renderer.present()
		!quit && !testMode
	}
}

// ══════════════════════════════════════════════════════════════
// MARK: Texture builders
// ══════════════════════════════════════════════════════════════

/** Orange-framed yellow-fill 64×64 sprite, rendered to a texture target. */
fun createSpriteTexture(renderer: SDL3.Renderer): SDL3.Texture {
	val tex = renderer.createTexture(64, 64, SDL3.PixelFormat.RGBA8888, SDL3.TextureAccess.Target)
	renderer.setTarget(tex)
	renderer.setDrawColor(255, 80, 0, 255)
	renderer.clear()
	renderer.setDrawColor(255, 220, 0, 255)
	renderer.fillRect(SDL3.FRect(8.0f, 8.0f, 48.0f, 48.0f))
	renderer.clearTarget()
	return tex
}

/** 4 × 32×32 px colour tiles laid out on a 128×32 atlas texture. */
fun createAtlasTexture(renderer: SDL3.Renderer): SDL3.Texture {
	val tex = renderer.createTexture(128, 32, SDL3.PixelFormat.RGBA8888, SDL3.TextureAccess.Target)
	renderer.setTarget(tex)
	renderer.setDrawColor(200, 60,  60,  255); renderer.fillRect(SDL3.FRect(0.0f,  0.0f, 32.0f, 32.0f))
	renderer.setDrawColor(60,  200, 60,  255); renderer.fillRect(SDL3.FRect(32.0f, 0.0f, 32.0f, 32.0f))
	renderer.setDrawColor(60,  100, 220, 255); renderer.fillRect(SDL3.FRect(64.0f, 0.0f, 32.0f, 32.0f))
	renderer.setDrawColor(220, 190, 0,   255); renderer.fillRect(SDL3.FRect(96.0f, 0.0f, 32.0f, 32.0f))
	renderer.clearTarget()
	return tex
}
