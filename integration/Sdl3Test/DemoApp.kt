package test

import sdl3.*

// ══════════════════════════════════════════════════════════════
// MARK: DemoApp
// ══════════════════════════════════════════════════════════════

/**
 * Holds all game resources (handles borrowed from main) and mutable game state.
 * Call the event handlers, update(), and render() each frame.
 */
class DemoApp(
	val window:        SDL3.Window,
	val renderer:      SDL3.Renderer,
	val cursorDefault: SDL3.Cursor,
	val cursorPointer: SDL3.Cursor,
	val sprite:        SDL3.Texture,
	val atlas:         SDL3.Texture,
	val background:    SDL3.Color,
	val boxColor:      SDL3.Color,
	val hoverColor:    SDL3.Color,
	val outlineColor:  SDL3.Color,
	val crosshairCol:  SDL3.Color,
	val leashColor:    SDL3.Color,
	var box:           SDL3.FRect,
	var mouseX:        Float        = 0.0f,
	var mouseY:        Float        = 0.0f,
	var hoverPulse:    Float        = 0.0f,
	var hovering:      Boolean      = false,
	var quit:          Boolean      = false,
	var wasHovering:   Boolean      = false,
	var fullscreen:    Boolean      = false,
	var logicalMode:   Boolean      = false,
	var frameCount:    Int          = 0,
	var titleTimer:    Float        = 0.0f,
	var atlasTileTimer: Float       = 0.0f,
	var atlasTileIdx:   Int         = 0,
	var movState:      MovementState = MovementState(),
	var pulse:         PulseState    = PulseState(),
	var spriteState:   SpriteState   = SpriteState()
) {
	// ══════════════════════════════════════════════════════════
	// MARK: Event handlers (primitive args — SDL_Event is C-only)
	// ══════════════════════════════════════════════════════════

	fun onQuit() { quit = true }

	fun onKeyDown(scancode: Int) {
		when (scancode) {
			SDL3.Scancode.Escape                  -> quit = true
			SDL3.Scancode.Left,  SDL3.Scancode.A  -> movState.left  = true
			SDL3.Scancode.Right, SDL3.Scancode.D  -> movState.right = true
			SDL3.Scancode.Up,    SDL3.Scancode.W  -> movState.up    = true
			SDL3.Scancode.Down,  SDL3.Scancode.S  -> movState.down  = true
			SDL3.Scancode.F -> {
				fullscreen = !fullscreen
				window.setFullscreen(fullscreen)
			}
			SDL3.Scancode.L -> {
				logicalMode = !logicalMode
				if (logicalMode) renderer.setLogicalSize(800, 600) else renderer.clearLogicalSize()
			}
		}
	}

	fun onKeyUp(scancode: Int) {
		when (scancode) {
			SDL3.Scancode.Left,  SDL3.Scancode.A  -> movState.left  = false
			SDL3.Scancode.Right, SDL3.Scancode.D  -> movState.right = false
			SDL3.Scancode.Up,    SDL3.Scancode.W  -> movState.up    = false
			SDL3.Scancode.Down,  SDL3.Scancode.S  -> movState.down  = false
		}
	}

	fun onMouseMotion(x: Float, y: Float) {
		mouseX = x
		mouseY = y
	}

	fun onMouseButtonDown(btn: Int, mx: Float, my: Float) {
		when (btn) {
			SDL3.Mouse.Left  -> {
				if (box.contains(SDL3.FPoint(mx, my))) {
					box = box.copy(x = mx - box.w / 2.0f, y = my - box.h / 2.0f)
					println(box)
				}
			}
			SDL3.Mouse.Right -> pulse.spawn(mx, my)
		}
	}

	fun onMouseWheel(wy: Float) {
		val scale = if (wy > 0.0f) 1.15f else 0.87f
		val newW  = box.w * scale
		val cw    = if (newW < 40.0f) 40.0f else if (newW > 500.0f) 500.0f else newW
		val newH  = box.h * scale
		val ch    = if (newH < 40.0f) 40.0f else if (newH > 500.0f) 500.0f else newH
		val cx    = box.x + box.w / 2.0f
		val cy    = box.y + box.h / 2.0f
		box       = SDL3.FRect(cx - cw / 2.0f, cy - ch / 2.0f, cw, ch)
	}

	// ══════════════════════════════════════════════════════════
	// MARK: Update
	// ══════════════════════════════════════════════════════════

	fun update(dt: Float) {
		updateFpsTitle(dt)
		moveBox(dt)
		if (SDL3.Mouse.isButtonDown(SDL3.Mouse.Right)) pulse.spawn(mouseX, mouseY)
		updateHoverCursor()
		pulse.update(dt)
		spriteState.update(dt, mouseX, mouseY)
		updateHoverPulse(dt)
		updateAtlasTile(dt)
	}

	private fun updateFpsTitle(dt: Float) {
		frameCount++
		titleTimer += dt
		if (titleTimer >= 1.0f) {
			val fps   = (frameCount.toFloat() / titleTimer).toInt()
			val wsize = window.getSize()
			window.setTitle("SDL3 Demo | FPS: $fps | ${wsize.x.toInt()}x${wsize.y.toInt()} | WASD RClick F=full L=lbox Z=zoom Spc=spin")
			titleTimer = 0.0f
			frameCount = 0
		}
	}

	private fun moveBox(dt: Float) {
		val ws  = renderer.outputSize()
		var dx  = 0.0f
		var dy  = 0.0f
		when {
			movState.left  -> dx -= 200.0f * dt
			movState.right -> dx += 200.0f * dt
			movState.up    -> dy -= 200.0f * dt
			movState.down  -> dy += 200.0f * dt
		}
		if (dx != 0.0f || dy != 0.0f) {
			val nx = box.x + dx
			val ny = box.y + dy
			val bx = if (nx < 0.0f) 0.0f else if (nx + box.w > ws.x) ws.x - box.w else nx
			val by = if (ny < 0.0f) 0.0f else if (ny + box.h > ws.y) ws.y - box.h else ny
			box = box.copy(x = bx, y = by)
		}
	}

	private fun updateHoverCursor() {
		hovering = box.contains(SDL3.FPoint(mouseX, mouseY))
		if (hovering != wasHovering) {
			if (hovering) cursorPointer.activate() else cursorDefault.activate()
			wasHovering = hovering
		}
	}

	private fun updateHoverPulse(dt: Float) {
		if (hovering) {
			hoverPulse += dt * 0.6f
			if (hoverPulse > 1.0f) hoverPulse = 0.0f
		} else {
			hoverPulse = 0.0f
		}
	}

	private fun updateAtlasTile(dt: Float) {
		atlasTileTimer += dt
		if (atlasTileTimer >= 0.5f) {
			atlasTileTimer -= 0.5f
			atlasTileIdx = (atlasTileIdx + 1) % 4
		}
	}

	// ══════════════════════════════════════════════════════════
	// MARK: Render
	// ══════════════════════════════════════════════════════════

	fun render(trailX: FloatArray, trailY: FloatArray, trailAngle: FloatArray, trailHead: Int) {
		val ws          = renderer.outputSize()
		val renderScale = if (isKeyDown(SDL3.Scancode.Z)) 2.0f else 1.0f

		renderer.setScale(renderScale, renderScale)
		renderer.setDrawColor(background)
		renderer.clear()

		pulse.render(renderer)
		renderer.renderBox(box, hovering, hoverPulse, boxColor, hoverColor, outlineColor)
		renderer.renderLeash(box, mouseX, mouseY, movState, leashColor)
		renderer.renderDirectionArrow(box, mouseX, mouseY)
		renderer.renderCrosshair(mouseX, mouseY, crosshairCol)

		renderer.setDrawColor(SDL3.Color(255, 255, 255, 60))
		renderer.drawLine(spriteState.posX, spriteState.posY, mouseX, mouseY)

		renderTrail(trailX, trailY, trailAngle, trailHead)
		spriteState.applyMods(sprite)
		spriteState.render(renderer, sprite)

		// HUD is always 1:1 (no zoom)
		renderer.setScale(1.0f, 1.0f)
		renderer.renderAtlasHud(atlas, atlasTileIdx, ws)
		renderer.renderMinimap(ws, box, spriteState.posX, spriteState.posY, mouseX, mouseY, hovering, boxColor, hoverColor)

		renderer.present()
	}

	private fun renderTrail(trailX: FloatArray, trailY: FloatArray, trailAngle: FloatArray, trailHead: Int) {
		sprite.setColorMod(255, 255, 255)
		for (i in 0 until 24) {
			val tidx   = (trailHead + i) % 24
			sprite.setAlphaMod((i + 1) * 10)
			val ghostDst = SDL3.FRect(trailX[tidx] - 16.0f, trailY[tidx] - 16.0f, 32.0f, 32.0f)
			renderer.renderTextureRotated(sprite, ghostDst, trailAngle[tidx], SDL3.Flip.None)
		}
	}
}
