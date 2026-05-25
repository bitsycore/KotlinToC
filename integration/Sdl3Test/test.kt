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

	val cursorDefault = SDL3.createSystemCursor(SDL3.SystemCursor.Default)
	defer { cursorDefault.destroy() }
	val cursorPointer = SDL3.createSystemCursor(SDL3.SystemCursor.Pointer)
	defer { cursorPointer.destroy() }

	val sprite = createSpriteTexture(renderer)
	defer { sprite.destroy() }
	val atlas = createAtlasTexture(renderer)
	defer { atlas.destroy() }

	val logBuf1 = Array<Byte>.allocWith(Heap, 2048)
	defer { Heap.freeMem(logBuf1) }
	val logBuf2 = Array<Byte>.allocWith(Heap, 2048)
	defer { Heap.freeMem(logBuf2) }
	var logArena1 = Arena(logBuf1.ptr(), logBuf1.size)
	var logArena2 = Arena(logBuf2.ptr(), logBuf2.size)
	val frameBuf = Array<Byte>.allocWith(Heap, 1024)
	defer { Heap.freeMem(frameBuf) }
	var frameArena = Arena(frameBuf.ptr(), frameBuf.size)

	val app = DemoApp(
		window        = window,
		renderer      = renderer,
		cursorDefault = cursorDefault,
		cursorPointer = cursorPointer,
		sprite        = sprite,
		atlas         = atlas,
		background    = SDL3.Color(30, 30, 30, 255),
		boxColor      = SDL3.Color(0, 128, 255, 255),
		hoverColor    = SDL3.Color(255, 200, 0, 255),
		outlineColor  = SDL3.Color(255, 255, 255, 128),
		crosshairCol  = SDL3.Color(180, 180, 180, 200),
		leashColor    = SDL3.Color(100, 100, 220, 120),
		box           = SDL3.FRect(300.0f, 225.0f, 200.0f, 150.0f),
		logArena1     = logArena1.ptr(),
		logArena2     = logArena2.ptr(),
		frameArena    = frameArena.ptr()
	)

	// Sprite angle trail — kept local to avoid FloatArray-in-struct issues
	val trailX     = FloatArray(24)
	val trailY     = FloatArray(24)
	val trailAngle = FloatArray(24)
	var trailHead  = 0

	gameLoop(60) { dt ->
		pollEvents { event ->
			when (event.type) {
				SDL3.Event.Quit            -> app.onQuit()
				SDL3.Event.KeyDown         -> app.onKeyDown(event.key.scancode)
				SDL3.Event.KeyUp           -> app.onKeyUp(event.key.scancode)
				SDL3.Event.MouseMotion     -> app.onMouseMotion(event.motion.x, event.motion.y)
				SDL3.Event.MouseButtonDown -> app.onMouseButtonDown(event.button.button, event.button.x, event.button.y)
				SDL3.Event.MouseWheel      -> app.onMouseWheel(event.wheel.y)
			}
		}

		app.update(dt)

		// Record trail before spinning so ghosts show the pre-spin pose
		trailX[trailHead]     = app.spriteState.posX
		trailY[trailHead]     = app.spriteState.posY
		trailAngle[trailHead] = app.spriteState.angle
		trailHead = (trailHead + 1) % 24

		val spinSpeed = if (isKeyDown(SDL3.Scancode.Space)) 270.0f else 90.0f
		app.spriteState.spin(dt, spinSpeed)

		app.render(trailX, trailY, trailAngle, trailHead)
		!app.quit && !testMode
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
