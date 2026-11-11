@file:OptIn(ExperimentalForeignApi::class)

package ktc.sdl3

import kotlinx.cinterop.*
import sdl3.*

// ==================
// MARK: Game loop
// ==================

/**
 * Run a delta-timed game loop capped at targetFps.
 * The block receives dt (seconds since last frame) and returns true to keep running.
 * Inline - the block lambda expands at the call site with no extra call overhead.
 */
inline fun gameLoop(targetFps: Int = 60, block: (Float) -> Boolean) {
	val vFrameMs = 1000L / targetFps.toLong()
	var vLastTick = SDL3.ticks()
	var vRunning = true
	while (vRunning) {
		val vNow = SDL3.ticks()
		val vDelta = (vNow - vLastTick).toFloat() / 1000.0f
		vLastTick = vNow
		vRunning = block(vDelta)
		val vElapsed = SDL3.ticks() - vNow
		if (vElapsed < vFrameMs) SDL3.delay((vFrameMs - vElapsed).toInt())
	}
}

// ==================
// MARK: Events
// ==================

/** The one event the polling loop reads into; SDL events are handled on one thread. */
@PublishedApi
internal val gEvent: SDL_Event = nativeHeap.alloc()

/** Drain SDL's event queue, calling block for each event. Inline - block expands at call site. */
inline fun pollEvents(block: (SDL_Event) -> Unit) {
	while (SDL_PollEvent(gEvent.ptr)) block(gEvent)
}

// ==================
// MARK: Keyboard
// ==================

/** True if the key with the given scancode (SDL3.Scancode) is currently held down (polled, not event-based). */
fun isKeyDown(scancode: UInt): Boolean {
	val vCount = onStack<IntVar>()
	val vState = SDL_GetKeyboardState(vCount.ptr) ?: return false
	if (scancode.toInt() < 0 || scancode.toInt() >= vCount.value) return false
	return vState[scancode.toInt()]
}

// ==================
// MARK: Mouse
// ==================

/** Query current mouse position. Snapshot at call time. */
fun SDL3.Mouse.position(): SDL3.FPoint {
	val vX = onStack<FloatVar>()
	val vY = onStack<FloatVar>()
	SDL_GetMouseState(vX.ptr, vY.ptr)
	return SDL3.FPoint(vX.value, vY.value)
}

/** True if the given mouse button (SDL3.Mouse.Left…) is currently held down (polled, not event-based). */
fun SDL3.Mouse.isButtonDown(button: Int): Boolean {
	val vMask = SDL_GetMouseState(null, null)
	return (vMask and (1u shl (button - 1))) != 0u
}
