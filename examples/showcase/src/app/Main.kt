@file:OptIn(ExperimentalForeignApi::class)

package showcase.app

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.sdl3.*
import sdl3.SDL_DestroySurface
import sdl3.SDL_RenderReadPixels
import sdl3.SDL_SaveBMP
import sdl3.SDL_SetRenderVSync
import sdl3.SDL_SetWindowTitle
import showcase.core.runInConsole
import showcase.scenes.BunnyScene
import showcase.scenes.DataScene
import showcase.scenes.FractalScene
import showcase.scenes.LifeScene
import showcase.scenes.MenuScene
import showcase.scenes.ParticlesScene
import showcase.scenes.PhysicsScene
import showcase.scenes.TestsScene

// ==================
// MARK: Entry
// ==================

/**
 * Where the program starts (the module file says so).
 *
 * - no argument: the window, until it is closed or Escape is pressed on the menu;
 * - `--auto`: every page in turn, each doing what a user would for a few seconds, then a report;
 *   with `--shot <directory>`, a picture of each page is saved there (BMP);
 * - `--headless`: no window, the self-tests are run and printed; with `--log`, every check.
 */
fun start(args: Array<String>) {
	if ("--headless" in args) {
		println(if (runInConsole(full = "--log" in args)) "showcase: all passed" else "showcase: FAILURES")
		return
	}
	val vShots = args.indexOf("--shot").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
	val vApp = App(isAuto = "--auto" in args, shots = vShots)
	vApp.run()
}

// ==================
// MARK: The app
// ==================

/** The one app, which the pages reach it through: a global holds it, so they need no reference of their own. */
lateinit var gApp: App

/** The window, the pages and the loop that shows them. */
class App(val isAuto: Boolean, private val shots: String?) {
	val width = 1280
	val height = 720

	val window: SDL3.Window
	val renderer: SDL3.Renderer

	/** The pages, the menu first. */
	val scenes: List<Scene>

	private var mCurrent: Scene

	/** The page asked for: shown once the fade out of the current one is over. */
	private var mNext: Scene? = null

	/** From 0 to 1 while a page fades out, then back to 0 while the next one fades in. */
	private var mFade = 0.0f
	private var mFadingOut = false

	private val mMeter = FrameMeter()
	private var mQuit = false
	private var mVSync = true

	/** For `--auto`: the frame of the visit of the current page, and what each visit reported. */
	private var mAutoFrame = 0
	private val mReports = ArrayList<String>()

	init {
		gApp = this
		SDL3.initialize(SDL3.InitFlags.Video or SDL3.InitFlags.Events)
		window = SDL3.Window("ktc2 showcase", width, height)
		renderer = SDL3.Renderer(window)
		SDL_SetRenderVSync(renderer.handle, 1)
		renderer.setBlendMode(SDL3.BlendMode.Blend)
		val vMenu = MenuScene()
		scenes = listOf(vMenu, BunnyScene(), ParticlesScene(), PhysicsScene(), FractalScene(), LifeScene(), DataScene(), TestsScene())
		mCurrent = vMenu
		mCurrent.enter(this)
	}

	/** Shows [inScene], after a short fade. */
	fun show(inScene: Scene) {
		if (inScene === mCurrent || mNext != null) return
		mNext = inScene
		mFadingOut = true
	}

	/** Back to the menu, or out of the program from the menu. */
	fun back() {
		if (mCurrent === scenes[0]) mQuit = true else show(scenes[0])
	}

	fun run() {
		var vLast = Clock.now()
		while (!mQuit) {
			val vNow = Clock.now()
			val vSeconds = (vNow - vLast).toFloat().coerceAtMost(0.1f)
			vLast = vNow
			mMeter.tick(vSeconds)
			pollEvents { vEvent ->
				when (vEvent.kind) {
					SDL3.Event.Quit -> mQuit = true
					SDL3.Event.KeyDown -> when (vEvent.key.code) {
						SDL3.Scancode.Escape -> back()
						SDL3.Scancode.F2 -> {
							mVSync = !mVSync
							SDL_SetRenderVSync(renderer.handle, if (mVSync) 1 else 0)
						}
						else -> {
							val vNumber = numberOf(vEvent.key.code)
							if (vNumber != null && vNumber < scenes.size) show(scenes[vNumber]) else mCurrent.event(vEvent)
						}
					}
					else -> mCurrent.event(vEvent)
				}
			}
			if (isAuto) autoStep()
			mCurrent.update(vSeconds)
			stepFade(vSeconds)
			renderer.setDrawColor(Theme.background)
			renderer.clear()
			mCurrent.draw(renderer)
			drawFrame()
			renderer.present()
		}
		if (isAuto) {
			mReports.forEach { println(it) }
			println("showcase: every page shown")
		}
		renderer.destroy()
		window.destroy()
		SDL3.quit()
	}

	/** The number keys 1 to 9 as the page they show (1 the first after the menu); null for another key. */
	private fun numberOf(inKey: SDL3.Scancode): Int? = when (inKey) {
		SDL3.Scancode.Num1 -> 1
		SDL3.Scancode.Num2 -> 2
		SDL3.Scancode.Num3 -> 3
		SDL3.Scancode.Num4 -> 4
		SDL3.Scancode.Num5 -> 5
		SDL3.Scancode.Num6 -> 6
		SDL3.Scancode.Num7 -> 7
		SDL3.Scancode.Num0 -> 0
		else -> null
	}

	private fun stepFade(inSeconds: Float) {
		if (mFadingOut) {
			mFade += inSeconds * 6.0f
			if (mFade >= 1.0f) {
				mFade = 1.0f
				mFadingOut = false
				mCurrent.leave()
				mCurrent = mNext!!
				mNext = null
				mAutoFrame = 0
				mCurrent.enter(this)
				SDL_SetWindowTitle(window.handle, "ktc2 showcase: ${mCurrent.title}")
			}
		} else if (mFade > 0.0f) {
			mFade = (mFade - inSeconds * 6.0f).coerceAtLeast(0.0f)
		}
	}

	/** The bar at the top (the page, what it measures, the frame rate), the help at the bottom, and the fade. */
	private fun drawFrame() {
		renderer.setDrawColor(SDL3.Color(0x0E1117E6u))
		renderer.fillRect(SDL3.FRect(0.0f, 0.0f, width.toFloat(), kBarHeight))
		renderer.fillRect(SDL3.FRect(0.0f, height - kBarHeight, width.toFloat(), kBarHeight))
		renderer.text(10.0f, 6.0f, mCurrent.title, Theme.accent)
		val vRate = "${mMeter.fps} fps  ${(mMeter.worstMs * 10).toInt() / 10.0f} ms${if (mVSync) "" else "  no vsync"}"
		val vRateLeft = width - textWidth(vRate) - 10.0f
		renderer.text(vRateLeft, 6.0f, vRate, if (mMeter.fps >= 55) Theme.good else if (mMeter.fps >= 30) Theme.accent else Theme.bad)
		// What the page measures, between its name and the frame rate, smaller and cut to the room there is.
		val vStatusLeft = 10.0f + textWidth(mCurrent.title) + 20.0f
		renderer.text(vStatusLeft, 9.0f, fit(mCurrent.status(), vRateLeft - vStatusLeft - 20.0f, kSmallText), Theme.text, kSmallText)
		val vHelp = (if (mCurrent === scenes[0]) "1-7 a page   Esc quit   F2 vsync" else "Esc menu   1-7 a page   F2 vsync   ") + mCurrent.help
		renderer.text(10.0f, height - kBarHeight + 9.0f, fit(vHelp, width - 20.0f, kSmallText), Theme.dim, kSmallText)
		if (mFade > 0.0f) {
			renderer.setDrawColor(SDL3.Color(0u, 0u, 0u, (mFade * 255).toInt().toUByte()))
			renderer.fillRect(SDL3.FRect(0.0f, 0.0f, width.toFloat(), height.toFloat()))
		}
	}

	// ============
	//  --auto

	/** Lets the page act for a while, then saves its picture, notes its report and goes to the next page. */
	private fun autoStep() {
		if (mNext != null || mFade > 0.0f) return
		val vGoesOn = mCurrent.autoStep(mAutoFrame)
		mAutoFrame++
		if (vGoesOn) return
		val vIndex = scenes.indexOf(mCurrent)
		if (shots != null) {
			val vPixels = SDL_RenderReadPixels(renderer.handle, null)
			if (vPixels != null) {
				SDL_SaveBMP(vPixels, "$shots/${vIndex}-${mCurrent.title.lowercase().replace(' ', '-')}.bmp")
				SDL_DestroySurface(vPixels)
			}
		}
		mReports.add("${mCurrent.title}: ${mMeter.fps} fps. ${mCurrent.report()}")
		if (vIndex + 1 < scenes.size) show(scenes[vIndex + 1]) else mQuit = true
	}
}
