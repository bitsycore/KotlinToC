@file:OptIn(ExperimentalForeignApi::class)

package ktc.sdl3

import kotlinx.cinterop.*
import sdl3.*

// ==================
// MARK: Window
// ==================

fun SDL3.Window.destroy() {
	SDL_DestroyWindow(handle)
}

fun SDL3.Window.setTitle(title: String) {
	SDL_SetWindowTitle(handle, title)
}

fun SDL3.Window.setFullscreen(fullscreen: Boolean) {
	SDL_SetWindowFullscreen(handle, fullscreen)
}

fun SDL3.Window.getSize(): SDL3.FPoint {
	val vWidth = onStack<IntVar>()
	val vHeight = onStack<IntVar>()
	SDL_GetWindowSize(handle, vWidth.ptr, vHeight.ptr)
	return SDL3.FPoint(vWidth.value.toFloat(), vHeight.value.toFloat())
}

fun SDL3.Window.setResizable(resizable: Boolean) {
	SDL_SetWindowResizable(handle, resizable)
}

fun SDL3.Window.show() {
	SDL_ShowWindow(handle)
}

fun SDL3.Window.hide() {
	SDL_HideWindow(handle)
}

fun SDL3.Window.raise() {
	SDL_RaiseWindow(handle)
}

fun SDL3.Window.minimize() {
	SDL_MinimizeWindow(handle)
}

fun SDL3.Window.maximize() {
	SDL_MaximizeWindow(handle)
}

fun SDL3.Window.restore() {
	SDL_RestoreWindow(handle)
}

fun SDL3.Window.setPosition(x: Int, y: Int) {
	SDL_SetWindowPosition(handle, x, y)
}

fun SDL3.Window.getPosition(): SDL3.FPoint {
	val vX = onStack<IntVar>()
	val vY = onStack<IntVar>()
	SDL_GetWindowPosition(handle, vX.ptr, vY.ptr)
	return SDL3.FPoint(vX.value.toFloat(), vY.value.toFloat())
}

/** Flags bitmask for the window (SDL3.WindowFlags). */
fun SDL3.Window.getFlags(): ULong = SDL_GetWindowFlags(handle)

/** Set minimum window size for resizable windows. */
fun SDL3.Window.setMinimumSize(w: Int, h: Int) {
	SDL_SetWindowMinimumSize(handle, w, h)
}

/** Set maximum window size for resizable windows. */
fun SDL3.Window.setMaximumSize(w: Int, h: Int) {
	SDL_SetWindowMaximumSize(handle, w, h)
}

/** Center the window on its current display. */
fun SDL3.Window.centerOnScreen() {
	SDL_SetWindowPosition(handle, SDL_WINDOWPOS_CENTERED.toInt(), SDL_WINDOWPOS_CENTERED.toInt())
}

/** Set the window border/decoration state. */
fun SDL3.Window.setBordered(bordered: Boolean) {
	SDL_SetWindowBordered(handle, bordered)
}

/** Set window always-on-top. */
fun SDL3.Window.setAlwaysOnTop(onTop: Boolean) {
	SDL_SetWindowAlwaysOnTop(handle, onTop)
}

/** Current pixel size of the window (after scaling / HiDPI). */
fun SDL3.Window.getSizeInPixels(): SDL3.FPoint {
	val vWidth = onStack<IntVar>()
	val vHeight = onStack<IntVar>()
	SDL_GetWindowSizeInPixels(handle, vWidth.ptr, vHeight.ptr)
	return SDL3.FPoint(vWidth.value.toFloat(), vHeight.value.toFloat())
}

/** Set window opacity (0.0 = transparent, 1.0 = opaque). */
fun SDL3.Window.setOpacity(opacity: Float) {
	SDL_SetWindowOpacity(handle, opacity)
}

/** Get current window opacity. */
fun SDL3.Window.getOpacity(): Float = SDL_GetWindowOpacity(handle)

/** Enable or disable relative mouse mode (hides cursor, reports relative motion). */
fun SDL3.Window.setRelativeMouseMode(enabled: Boolean) {
	SDL_SetWindowRelativeMouseMode(handle, enabled)
}

/** Warp the mouse pointer to a position within this window. */
fun SDL3.Window.warpMouse(x: Float, y: Float) {
	SDL_WarpMouseInWindow(handle, x, y)
}

/** Confine the mouse cursor to this window. */
fun SDL3.Window.setMouseGrab(grabbed: Boolean) {
	SDL_SetWindowMouseGrab(handle, grabbed)
}
