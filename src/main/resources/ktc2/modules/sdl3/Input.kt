@file:OptIn(ExperimentalForeignApi::class)

package ktc2.sdl3

import kotlinx.cinterop.*
import ktc2.enumOf
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

/** What kind of event this is; `SDL3.Event.Unknown` for a kind that has no entry. */
val SDL_Event.kind: SDL3.Event get() = enumOf<SDL3.Event>(type) { it.value } ?: SDL3.Event.Unknown

/** The key of a key event (`event.key.code`); `SDL3.Scancode.Unknown` for a key that has no entry. */
val SDL_KeyboardEvent.code: SDL3.Scancode get() = enumOf<SDL3.Scancode>(scancode) { it.value } ?: SDL3.Scancode.Unknown

/** The button of a mouse button event (`event.button.pressed`); null for one beyond the five that have a name. */
val SDL_MouseButtonEvent.pressed: SDL3.MouseButton? get() = enumOf<SDL3.MouseButton>(button.toInt()) { it.number }

// ==================
// MARK: Keyboard
// ==================

/** True if the key is currently held down (polled, not event-based). */
fun isKeyDown(scancode: SDL3.Scancode): Boolean {
	val vCount = onStack<IntVar>()
	val vState = SDL_GetKeyboardState(vCount.ptr) ?: return false
	val vIndex = scancode.value.toInt()
	if (vIndex < 0 || vIndex >= vCount.value) return false
	return vState[vIndex]
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

/** True if the given mouse button is currently held down (polled, not event-based). */
fun SDL3.Mouse.isButtonDown(button: SDL3.MouseButton): Boolean {
	val vMask = SDL_GetMouseState(null, null)
	return (vMask and (1u shl (button.number - 1))) != 0u
}
