package test

import sdl3.*

data class MovementState(
	var left: Boolean = false,
	var right: Boolean = false,
	var up: Boolean = false,
	var down: Boolean = false
)

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

	// Sprite: render-target texture (orange frame + yellow fill)
	val sprite = renderer.createTexture(64, 64, SDL3.PixelFormat.RGBA8888, SDL3.TextureAccess.Target)
	defer { sprite.destroy() }
	renderer.setTarget(sprite)
	renderer.setDrawColor(255, 80, 0, 255)
	renderer.clear()
	renderer.setDrawColor(255, 220, 0, 255)
	renderer.fillRect(SDL3.FRect(8.0f, 8.0f, 48.0f, 48.0f))
	renderer.clearTarget()

	// Tile atlas: 4 × 32×32 px tiles on a 128×32 render-target texture
	val atlas = renderer.createTexture(128, 32, SDL3.PixelFormat.RGBA8888, SDL3.TextureAccess.Target)
	defer { atlas.destroy() }
	renderer.setTarget(atlas)
	renderer.setDrawColor(200, 60, 60, 255);  renderer.fillRect(SDL3.FRect(0.0f,  0.0f, 32.0f, 32.0f))
	renderer.setDrawColor(60, 200, 60, 255);  renderer.fillRect(SDL3.FRect(32.0f, 0.0f, 32.0f, 32.0f))
	renderer.setDrawColor(60, 100, 220, 255); renderer.fillRect(SDL3.FRect(64.0f, 0.0f, 32.0f, 32.0f))
	renderer.setDrawColor(220, 190, 0, 255);  renderer.fillRect(SDL3.FRect(96.0f, 0.0f, 32.0f, 32.0f))
	renderer.clearTarget()

	// Colors
	val background   = SDL3.Color(30, 30, 30, 255)
	val boxColor     = SDL3.Color(0, 128, 255, 255)
	val hoverColor   = SDL3.Color(255, 200, 0, 255)
	val outlineColor = SDL3.Color(255, 255, 255, 128)
	val crosshairCol = SDL3.Color(180, 180, 180, 200)
	val leashColor   = SDL3.Color(100, 100, 220, 120)

	var box            = SDL3.FRect(300.0f, 225.0f, 200.0f, 150.0f)
	var mouseX         = 0.0f
	var mouseY         = 0.0f
	var spriteAngle    = 0.0f
	var spriteAlpha    = 255.0f
	var spriteAlphaDir = -1.0f   // direction of alpha pulse
	var spritePos      = SDL3.FPoint(mouseX, mouseY)   // smooth-follows cursor
	var hoverPulse     = 0.0f   // 0..1 hover ring radius offset
	var quit           = false
	var wasHovering    = false
	var fullscreen     = false

	// FPS tracking
	var frameCount = 0
	var titleTimer = 0.0f

	// Glow pulse (right-click spawns/resets; expands and fades)
	var pulseActive     = false
	var pulseX          = 0.0f
	var pulseY          = 0.0f
	var pulseRadius     = 0.0f
	var pulseAlpha      = 0.0f
	var pulseR          = 255
	var pulseG          = 100
	var pulseB          = 0
	var pulseColorCycle = 0

	// Sprite color-mod cycle (changes every second)
	var colorCycleTime  = 0.0f

	val movState = MovementState()
	val speed    = 200.0f

	// Sprite position + angle trail (circular buffer of 24 past states)
	val trailX     = FloatArray(24)
	val trailY     = FloatArray(24)
	val trailAngle = FloatArray(24)
	var trailHead  = 0

	// Tile cycling state
	var atlasTileTimer = 0.0f
	var atlasTileIdx   = 0

	// Logical presentation and zoom state
	var logicalMode = false

	gameLoop(60) { dt ->
		// FPS + help text in window title (updated once per second)
		frameCount++
		titleTimer += dt
		if (titleTimer >= 1.0f) {
			val fps   = (frameCount.toFloat() / titleTimer).toInt()
			val wsize = window.getSize()
			window.setTitle("SDL3 Demo | FPS: $fps | ${wsize.x.toInt()}x${wsize.y.toInt()} | WASD RClick F=full L=lbox Z=zoom Spc=spin")
			titleTimer = 0.0f
			frameCount = 0
		}

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
						SDL3.Mouse.Right -> {
							// Spawn glow pulse at cursor; cycle through 4 colours
							when (pulseColorCycle % 4) {
								0 -> { pulseR = 255; pulseG = 80;  pulseB = 0   }
								1 -> { pulseR = 0;   pulseG = 200; pulseB = 255 }
								2 -> { pulseR = 200; pulseG = 0;   pulseB = 255 }
								3 -> { pulseR = 0;   pulseG = 255; pulseB = 100 }
							}
							pulseColorCycle++
							pulseX      = mx
							pulseY      = my
							pulseRadius = 4.0f
							pulseAlpha  = 255.0f
							pulseActive = true
						}
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

		// Delta movement clamped to render-output bounds
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

		// Held right-button: spawn pulses continuously (real-time polling, not events)
		if (SDL3.Mouse.isButtonDown(SDL3.Mouse.Right)) {
			when (pulseColorCycle % 4) {
				0 -> { pulseR = 255; pulseG = 80;  pulseB = 0   }
				1 -> { pulseR = 0;   pulseG = 200; pulseB = 255 }
				2 -> { pulseR = 200; pulseG = 0;   pulseB = 255 }
				3 -> { pulseR = 0;   pulseG = 255; pulseB = 100 }
			}
			pulseColorCycle++
			pulseX      = mouseX
			pulseY      = mouseY
			pulseRadius = 4.0f
			pulseAlpha  = 255.0f
			pulseActive = true
		}

		val hovering = box.contains(SDL3.FPoint(mouseX, mouseY))

		// Swap cursor on hover-state transition
		if (hovering != wasHovering) {
			if (hovering) cursorPointer.activate() else cursorDefault.activate()
			wasHovering = hovering
		}

		// Sprite alpha pulse (80–255, ~1.4 s period)
		spriteAlpha += spriteAlphaDir * 160.0f * dt
		if (spriteAlpha <= 70.0f)  { spriteAlpha = 70.0f;  spriteAlphaDir =  1.0f }
		if (spriteAlpha >= 255.0f) { spriteAlpha = 255.0f; spriteAlphaDir = -1.0f }
		sprite.setAlphaMod(spriteAlpha.toInt())

		// Smooth sprite follow: lerp toward cursor at ~8× per second
		val lerpT = clamp(dt * 8.0f, 0.0f, 1.0f)
		spritePos = spritePos.lerpTo(SDL3.FPoint(mouseX, mouseY), lerpT)

		// Record sprite position + angle in trail circular buffer
		trailX[trailHead]     = spritePos.x
		trailY[tdrailHead]     = spritePos.y
		trailAngle[trailHead] = spriteAngle
		trailHead = (trailHead + 1) % 24

		// Cycle atlas tile every 0.5 s
		atlasTileTimer += dt
		if (atlasTileTimer >= 0.5f) {
			atlasTileTimer -= 0.5f
			atlasTileIdx = (atlasTileIdx + 1) % 4
		}

		// Hover pulse: ring expands outward then resets
		if (hovering) {
			hoverPulse += dt * 0.6f
			if (hoverPulse > 1.0f) hoverPulse = 0.0f
		} else {
			hoverPulse = 0.0f
		}

		// Sprite color-mod cycles hue-like over time (red↔green↔blue tint)
		colorCycleTime += dt
		val ct = colorCycleTime
		val cModR = ((c.sinf(ct * 1.1f) * 0.5f + 0.5f) * 255.0f).toInt()
		val cModG = ((c.sinf(ct * 1.1f + 2.094f) * 0.5f + 0.5f) * 255.0f).toInt()
		val cModB = ((c.sinf(ct * 1.1f + 4.189f) * 0.5f + 0.5f) * 255.0f).toInt()
		sprite.setColorMod(cModR, cModG, cModB)

		// ── Render ────────────────────────────────────────────────
		// Z key: 2× zoom applied to all main-scene content (setScale demo)
		val renderScale = if (isKeyDown(SDL3.Scancode.Z)) 2.0f else 1.0f
		renderer.setScale(renderScale, renderScale)
		renderer.setDrawColor(background)
		renderer.clear()

		// Glow pulse: expand + fade with additive blend (no dark edges)
		if (pulseActive) {
			renderer.setBlendMode(SDL3.BlendMode.Add)
			val alpha = pulseAlpha.toInt()
			renderer.setDrawColor(pulseR, pulseG, pulseB, alpha)
			renderer.fillCircle(pulseX, pulseY, pulseRadius)
			renderer.setDrawColor(pulseR, pulseG, pulseB, alpha / 2)
			renderer.drawCircle(pulseX, pulseY, pulseRadius + 6.0f)
			renderer.setBlendMode(SDL3.BlendMode.None)

			pulseAlpha  -= 200.0f * dt
			pulseRadius += 90.0f  * dt
			if (pulseAlpha <= 0.0f) pulseActive = false
		}

		// Box (rounded corners via Draw helpers)
		renderer.setDrawColor(if (hovering) hoverColor else boxColor)
		renderer.fillRoundedRect(box, 12.0f)
		renderer.setDrawColor(outlineColor)
		renderer.drawRoundedRect(box, 12.0f)

		// Leash: color reflects whether box is moving toward cursor (dot product demo)
		val center   = box.center()
		val lMovX    = if (movState.right) 1.0f else if (movState.left) -1.0f else 0.0f
		val lMovY    = if (movState.down) 1.0f else if (movState.up) -1.0f else 0.0f
		val lMovDir  = SDL3.FPoint(lMovX, lMovY).normalized()
		val lAVec    = SDL3.FPoint(mouseX - center.x, mouseY - center.y).normalized()
		val lApproach = lMovDir.dot(lAVec)
		val lColor   = if (lApproach > 0.3f) SDL3.Color(50, 220, 100, 160) else if (lApproach < -0.3f) SDL3.Color(200, 80, 50, 160) else leashColor
		renderer.setDrawColor(lColor)
		renderer.drawLine(center.x, center.y, mouseX, mouseY)

		// Distance label: small circle at box center
		renderer.setDrawColor(SDL3.Color(255, 255, 255, 180))
		renderer.drawCircle(center.x, center.y, 5.0f)

		// Distance indicator circle (radius = center-to-cursor distance, capped at 120)
		val dist = center.distanceTo(SDL3.FPoint(mouseX, mouseY))
		val indicatorR = if (dist > 120.0f) 120.0f else dist
		renderer.setDrawColor(SDL3.Color(255, 255, 255, 40))
		renderer.drawCircle(center.x, center.y, indicatorR)

		// Crosshair + ring on cursor
		renderer.setDrawColor(crosshairCol)
		renderer.drawLine(mouseX - 12.0f, mouseY,         mouseX + 12.0f, mouseY)
		renderer.drawLine(mouseX,         mouseY - 12.0f, mouseX,         mouseY + 12.0f)
		renderer.drawCircle(mouseX, mouseY, 8.0f)

		// Trail line from sprite smooth-pos to cursor (shows the lag)
		renderer.setDrawColor(SDL3.Color(255, 255, 255, 60))
		renderer.drawLine(spritePos.x, spritePos.y, mouseX, mouseY)

		// Hover pulse ring: expands from box outline outward then fades
		if (hovering && hoverPulse > 0.0f) {
			val pulseGrow = hoverPulse * 20.0f
			val pulseA    = (255.0f * (1.0f - hoverPulse)).toInt()
			renderer.setDrawColor(SDL3.Color(255, 200, 50, pulseA))
			renderer.drawRoundedRect(box.grow(pulseGrow), 12.0f + pulseGrow)
		}

		// Direction arrow: filled gradient triangle pointing from box center to cursor
		val adx   = mouseX - center.x
		val ady   = mouseY - center.y
		val adist = center.distanceTo(SDL3.FPoint(mouseX, mouseY))
		if (adist > 30.0f) {
			val aDir  = SDL3.FPoint(adx, ady).normalized()
			val anx   = aDir.x
			val any   = aDir.y
			val reach = if (adist < 100.0f) adist - 20.0f else 80.0f
			val tipX  = center.x + anx * reach
			val tipY  = center.y + any * reach
			val baseX = center.x + anx * 16.0f
			val baseY = center.y + any * 16.0f
			val perpX = -any * 10.0f
			val perpY =  anx * 10.0f
			renderer.setBlendMode(SDL3.BlendMode.Blend)
			renderer.fillTriangle(
				tipX,           tipY,           1.0f, 1.0f, 1.0f, 0.9f,
				baseX + perpX,  baseY + perpY,  0.2f, 0.5f, 1.0f, 0.0f,
				baseX - perpX,  baseY - perpY,  0.2f, 0.5f, 1.0f, 0.0f
			)
			renderer.setBlendMode(SDL3.BlendMode.None)
		}

		// Ghost sprite trail: fading echoes of past sprite positions, rotated at recorded angle
		sprite.setColorMod(255, 255, 255)
		for (i in 0 until 24) {
			val tidx   = (trailHead + i) % 24
			val talpha = (i + 1) * 10
			sprite.setAlphaMod(talpha)
			val ghostDst = SDL3.FRect(trailX[tidx] - 16.0f, trailY[tidx] - 16.0f, 32.0f, 32.0f)
			renderer.renderTextureRotated(sprite, ghostDst, trailAngle[tidx], SDL3.Flip.None)
		}
		sprite.setColorMod(cModR, cModG, cModB)
		sprite.setAlphaMod(spriteAlpha.toInt())

		// Rotating, pulsing, color-modulated sprite at smooth-follow position
		// Space key boosts rotation speed 3×
		val spinSpeed = if (isKeyDown(SDL3.Scancode.Space)) 270.0f else 90.0f
		spriteAngle += spinSpeed * dt
		val spriteDst = SDL3.FRect(spritePos.x - 32.0f, spritePos.y - 32.0f, 64.0f, 64.0f)
		renderer.renderTextureRotated(sprite, spriteDst, spriteAngle, SDL3.Flip.None)

		// Reset scale for HUD elements (atlas + minimap should always be 1:1)
		renderer.setScale(1.0f, 1.0f)

		// Tile atlas: source-rect crop demo — cycling tile displayed at bottom-left
		val atlasSrc = SDL3.FRect(atlasTileIdx.toFloat() * 32.0f, 0.0f, 32.0f, 32.0f)
		val atlasDst = SDL3.FRect(10.0f, ws.y - 58.0f, 48.0f, 48.0f)
		renderer.renderTexture(atlas, atlasSrc, atlasDst)
		renderer.setDrawColor(SDL3.Color(255, 255, 255, 80))
		renderer.drawRect(atlasDst)

		// Minimap in top-right corner (setViewport demo)
		val mmW        = 150
		val mmH        = 100
		val mmVpX      = ws.x.toInt() - mmW - 10
		val mmViewport = SDL3.Rect(mmVpX, 10, mmW, mmH)
		renderer.setViewport(mmViewport)
		renderer.setDrawColor(15, 15, 35, 255)
		renderer.fillRect(SDL3.FRect(0.0f, 0.0f, mmW.toFloat(), mmH.toFloat()))
		renderer.setDrawColor(40, 40, 70, 255)
		renderer.drawRect(SDL3.FRect(0.0f, 0.0f, mmW.toFloat(), mmH.toFloat()))
		val mmScaleX = mmW.toFloat() / ws.x
		val mmScaleY = mmH.toFloat() / ws.y
		val mmBoxX   = box.x * mmScaleX
		val mmBoxY   = box.y * mmScaleY
		val mmBoxW   = box.w * mmScaleX
		val mmBoxH   = box.h * mmScaleY
		renderer.setDrawColor(if (hovering) hoverColor else boxColor)
		renderer.fillRect(SDL3.FRect(mmBoxX, mmBoxY, mmBoxW, mmBoxH))
		renderer.setDrawColor(SDL3.Color(255, 80, 80, 220))
		renderer.drawCircle(mouseX * mmScaleX, mouseY * mmScaleY, 3.0f)
		renderer.setDrawColor(SDL3.Color(100, 200, 100, 180))
		renderer.drawCircle(spritePos.x * mmScaleX, spritePos.y * mmScaleY, 2.0f)
		renderer.clearViewport()

		renderer.present()

		!quit && !testMode
	}
}
