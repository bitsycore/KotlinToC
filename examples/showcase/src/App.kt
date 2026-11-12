@file:OptIn(ExperimentalForeignApi::class)

package showcase.app

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.sdl3.*
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
 * - no argument: the window, until it is closed or Escape is pressed. A click on a group runs
 *   it and adds everything it did to the log, which the wheel, the arrows, Page Up and Down,
 *   Home and End, and the bar on its right move through;
 * - `--auto`: the window clicks each of its buttons by itself and moves through the log, then
 *   closes and reports; with `--shot <file.bmp>` it first saves a picture of what it shows;
 * - `--headless`: no window, the tests are run and printed; with `--log`, every check of them.
 */
fun start(args: Array<String>) {
	if ("--headless" in args) {
		println(if (runInConsole(full = "--log" in args)) "showcase: all passed" else "showcase: FAILURES")
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
	// With --auto: the buttons that run something, one every few frames so that each result is
	// drawn, then a walk through the log.
	val vToClick = vScreen.buttons.filter { it.action != kClearLog }
	var vNext = 0
	var vWalk = 0
	var vFarthest = 0
	gameLoop(60) {
		pollEvents { vEvent ->
			when (vEvent.type) {
				SDL3.Event.Quit -> vQuit = true
				SDL3.Event.KeyDown -> when (vEvent.key.scancode) {
					SDL3.Scancode.Escape -> vQuit = true
					SDL3.Scancode.Up -> vScreen.scroll(-1)
					SDL3.Scancode.Down -> vScreen.scroll(1)
					SDL3.Scancode.PageUp -> vScreen.page(-1)
					SDL3.Scancode.PageDown -> vScreen.page(1)
					SDL3.Scancode.Home -> vScreen.scrollToStart()
					SDL3.Scancode.End -> vScreen.scrollToEnd()
				}
				SDL3.Event.MouseWheel -> vScreen.scroll(-(vEvent.wheel.y * 3.0f).toInt())
				SDL3.Event.MouseMotion -> vScreen.move(vEvent.motion.x / kScale, vEvent.motion.y / kScale)
				SDL3.Event.MouseButtonDown -> {
					if (vEvent.button.button.toInt() == SDL3.Mouse.Left) vScreen.press(vEvent.button.x / kScale, vEvent.button.y / kScale)
				}
				SDL3.Event.MouseButtonUp -> vScreen.release()
			}
		}
		if (vIsAuto && vFrame % 3 == 2) {
			if (vNext < vToClick.size) {
				val vCenter = vToClick[vNext].bounds.center()
				vScreen.move(vCenter.x, vCenter.y)
				vScreen.press(vCenter.x, vCenter.y)
				vScreen.release()
				vNext++
			} else {
				// To the start, down by pages to the end, and back up a little.
				when (vWalk) {
					0 -> vScreen.scrollToStart()
					1, 2, 3 -> vScreen.page(1)
					4 -> vScreen.scrollToEnd()
					5 -> vScreen.scroll(-4)
					else -> vQuit = true
				}
				vFarthest = maxOf(vFarthest, vScreen.firstShown)
				vWalk++
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
		println("buttons clicked: ${vScreen.clicks} of ${vToClick.size}")
		println("groups run: ${vScreen.groupsRun}, failures: ${vScreen.failures}")
		println("states: " + vToClick.joinToString(" ") { it.state.name })
		println("log scrolled: ${vFarthest > 0 && vScreen.firstShown < vFarthest} (${vScreen.logSize > 100} lines enough to need it)")
		println("frames drawn: ${vFrame > vToClick.size}")
	}
	vRenderer.destroy()
	vWindow.destroy()
	SDL3.quit()
	if (vIsAuto) println(if (vScreen.failures == 0) "showcase: all passed" else "showcase: FAILURES")
}
