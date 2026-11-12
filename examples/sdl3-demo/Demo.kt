@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.sdl3.*

/** Opens a window, draws moving shapes for a second, and reports what happened. */
fun main() {
	SDL3.initialize(SDL3.InitFlags.Video or SDL3.InitFlags.Events)
	val vWindow = SDL3.Window("ktc2 + SDL3", 480, 320, SDL3.WindowFlags.Resizable)
	val vRenderer = SDL3.Renderer(vWindow)
	vWindow.setTitle("ktc2 demo")
	println("window ${vWindow.getSize()}")
	vRenderer.setBlendMode(SDL3.BlendMode.Blend)

	val vBounds = SDL3.FRect(0.0f, 0.0f, 480.0f, 320.0f)
	var vBall = SDL3.FPoint(40.0f, 60.0f)
	var vVelocity = SDL3.FPoint(180.0f, 120.0f)
	var vFrames = 0
	var vEvents = 0
	var vQuit = false
	val vStart = SDL3.ticks()

	gameLoop(60) { vDelta ->
		pollEvents { vEvent ->
			vEvents++
			if (vEvent.type == SDL3.Event.Quit) vQuit = true
			if (vEvent.type == SDL3.Event.KeyDown && vEvent.key.scancode == SDL3.Scancode.Escape) vQuit = true
		}
		if (isKeyDown(SDL3.Scancode.Space)) vVelocity = vVelocity.scale(1.01f)

		// Move, and bounce off the edges.
		var vNext = vBall.translate(vVelocity.x * vDelta, vVelocity.y * vDelta)
		if (!vBounds.shrink(20.0f).contains(vNext)) {
			if (vNext.x < 20.0f || vNext.x > 460.0f) vVelocity = SDL3.FPoint(-vVelocity.x, vVelocity.y)
			if (vNext.y < 20.0f || vNext.y > 300.0f) vVelocity = SDL3.FPoint(vVelocity.x, -vVelocity.y)
			vNext = vNext.clampTo(vBounds.shrink(20.0f))
		}
		vBall = vNext

		vRenderer.setDrawColor(SDL3.Color(0x202830FFu))
		vRenderer.clear()
		vRenderer.setDrawColor(90, 200, 250, 255)
		vRenderer.fillRoundedRect(SDL3.FRect(30.0f, 220.0f, 200.0f, 70.0f), 16.0f)
		vRenderer.setDrawColor(255, 255, 255, 255)
		vRenderer.drawRoundedRect(SDL3.FRect(30.0f, 220.0f, 200.0f, 70.0f), 16.0f)
		vRenderer.fillTriangle(300.0f, 290.0f, 440.0f, 290.0f, 370.0f, 180.0f, SDL3.FColor(0xFFB000C0u))
		vRenderer.setDrawColor(250, 90, 90, 255)
		vRenderer.fillCircle(vBall.x, vBall.y, 18.0f)
		vRenderer.setDrawColor(255, 255, 255, 255)
		vRenderer.drawCircle(vBall.x, vBall.y, 20.0f)
		vRenderer.drawLine(0.0f, 0.0f, vBall.x, vBall.y)
		vRenderer.drawDebugText(10.0f, 10.0f, "frame $vFrames")
		vRenderer.present()

		vFrames++
		!vQuit && SDL3.ticks() - vStart < 1000L
	}

	println("frames drawn: ${vFrames > 20}")
	println("ball inside: ${vBounds.contains(vBall)}")
	println("output ${vRenderer.outputSize()}")
	println(SDL3.Color(0x11223344u))
	println(SDL3.Rect(1, 2, 3, 4).toFRect().center())
	vRenderer.destroy()
	vWindow.destroy()
	SDL3.quit()
	println("done")
}
