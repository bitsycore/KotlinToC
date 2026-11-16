@file:OptIn(ExperimentalForeignApi::class)

package showcase.app

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.sdl3.*
import sdl3.SDL_Event

// ==================
// MARK: Scenes
// ==================

/**
 * A page of the showcase. The app calls [enter] when it is shown, then for each frame [event] for
 * each event, [update] with the seconds since the last frame and [draw]; [leave] when another page
 * is shown. A scene keeps its state between two visits.
 */
interface Scene {
	/** Its name, on the menu and in the bar at the top. */
	val title: String

	/** One line of what it shows, on the menu. */
	val summary: String

	/** The keys it knows, at the bottom of the window. */
	val help: String

	fun enter(app: App) {}

	fun leave() {}

	fun event(event: SDL_Event) {}

	fun update(seconds: Float)

	fun draw(renderer: SDL3.Renderer)

	/** What it measures now (counts, times), in the bar at the top. */
	fun status(): String = ""

	/**
	 * With `--auto`: what a user would do at frame [frame] of the visit (a key, a click). Returns
	 * false once the scene has shown what it has, after which the app goes to the next page.
	 */
	fun autoStep(frame: Int): Boolean = frame < 120

	/** With `--auto`: a line that says what the visit did, for the report at the end. */
	fun report(): String = ""
}

// ==================
// MARK: Timing
// ==================

/** Frames per second over the last second, and the longest frame of it. */
class FrameMeter {
	private var mFrames = 0
	private var mElapsed = 0.0f
	private var mWorst = 0.0f
	private var mWorstShown = 0.0f

	/** The frames per second of the last whole second. */
	var fps = 0
		private set

	/** The longest frame of the last whole second, in milliseconds. */
	val worstMs: Float get() = mWorstShown * 1000.0f

	fun tick(inSeconds: Float) {
		mFrames++
		mElapsed += inSeconds
		if (inSeconds > mWorst) mWorst = inSeconds
		if (mElapsed >= 1.0f) {
			fps = (mFrames / mElapsed).toInt()
			mWorstShown = mWorst
			mFrames = 0
			mElapsed = 0.0f
			mWorst = 0.0f
		}
	}
}

/** A high-resolution clock, in seconds. */
object Clock {
	private val mFrequency = SDL3.performanceFrequency().toDouble()

	fun now(): Double = SDL3.performanceCounter() / mFrequency
}
