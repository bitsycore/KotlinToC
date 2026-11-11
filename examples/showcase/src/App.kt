@file:OptIn(ExperimentalForeignApi::class)

package showcase.app

import kotlinx.cinterop.ExperimentalForeignApi
import ktc.sdl3.*
import sdl3.SDL_DestroySurface
import sdl3.SDL_RenderReadPixels
import sdl3.SDL_SaveBMP
import showcase.core.allGroups
import showcase.core.runInConsole

// ==================
// MARK: Entry
// ==================

/**
 * Where the program starts (the module file says so).
 *
 * - no argument: the window, until it is closed or Escape is pressed;
 * - `--auto`: the window clicks each of its buttons by itself, then closes and reports;
 *   with `--shot <file.bmp>` it first saves a picture of what it shows at the end;
 * - `--headless`: no window, the tests are run and printed.
 */
fun start(args: Array<String>) {
	if ("--headless" in args) {
		println(if (runInConsole()) "showcase: all passed" else "showcase: FAILURES")
		return
	}
	val vIsAuto = "--auto" in args
	val vShot = args.indexOf("--shot").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
	SDL3.initialize(SDL3.InitFlags.Video or SDL3.InitFlags.Events)
	val vWindow = SDL3.Window("ktc2 showcase", (kWidth * kScale).toInt(), (kHeight * kScale).toInt())
	val vRenderer = SDL3.Renderer(vWindow)
	vRenderer.setScale(kScale, kScale)
	val vScreen = Screen(allGroups())

	var vQuit = false
	var vFrame = 0
	// With --auto: the button to click next, one every few frames so that each result is drawn.
	var vNext = 0
	gameLoop(60) {
		pollEvents { vEvent ->
			when (vEvent.type) {
				SDL3.Event.Quit -> vQuit = true
				SDL3.Event.KeyDown -> if (vEvent.key.scancode == SDL3.Scancode.Escape) vQuit = true
				SDL3.Event.MouseMotion -> vScreen.hover(vEvent.motion.x / kScale, vEvent.motion.y / kScale)
				SDL3.Event.MouseButtonDown -> {
					if (vEvent.button.button.toInt() == SDL3.Mouse.Left) vScreen.click(vEvent.button.x / kScale, vEvent.button.y / kScale)
				}
			}
		}
		if (vIsAuto && vFrame % 3 == 2) {
			if (vNext < vScreen.buttons.size) {
				val vCenter = vScreen.buttons[vNext].bounds.center()
				vScreen.hover(vCenter.x, vCenter.y)
				vScreen.click(vCenter.x, vCenter.y)
				vNext++
			} else {
				vQuit = true
			}
		}
		vScreen.draw(vRenderer)
		if (vQuit && vShot != null) {
			// What was just drawn, read back from the renderer: the raw SDL functions next to the Kotlin API.
			val vPixels = SDL_RenderReadPixels(vRenderer.handle, null)
			if (vPixels != null) {
				println("picture saved: ${SDL_SaveBMP(vPixels, vShot)}")
				SDL_DestroySurface(vPixels)
			}
		}
		vFrame++
		!vQuit
	}

	if (vIsAuto) {
		println("buttons clicked: ${vScreen.clicks} of ${vScreen.buttons.size}")
		println("groups run: ${vScreen.groupsRun}, failures: ${vScreen.failures}")
		println("states: " + vScreen.buttons.joinToString(" ") { it.state.name })
		println("frames drawn: ${vFrame > vScreen.buttons.size}")
	}
	vRenderer.destroy()
	vWindow.destroy()
	SDL3.quit()
	if (vIsAuto) println(if (vScreen.failures == 0) "showcase: all passed" else "showcase: FAILURES")
}
