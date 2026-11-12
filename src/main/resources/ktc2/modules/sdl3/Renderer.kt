@file:OptIn(ExperimentalForeignApi::class)

package ktc2.sdl3

import kotlinx.cinterop.*
import sdl3.*

// ==================
// MARK: Values as C structs
// ==================

internal fun fill(inTarget: SDL_FRect, inRect: SDL3.FRect) {
	inTarget.x = inRect.x
	inTarget.y = inRect.y
	inTarget.w = inRect.w
	inTarget.h = inRect.h
}

internal fun fill(inTarget: SDL_Rect, inRect: SDL3.Rect) {
	inTarget.x = inRect.x
	inTarget.y = inRect.y
	inTarget.w = inRect.w
	inTarget.h = inRect.h
}

// ==================
// MARK: Renderer
// ==================

fun SDL3.Renderer.destroy() {
	SDL_DestroyRenderer(handle)
}

/** Set the draw colour for subsequent rendering calls. */
fun SDL3.Renderer.setDrawColor(r: Int, g: Int, b: Int, a: Int) {
	SDL_SetRenderDrawColor(handle, r.toUByte(), g.toUByte(), b.toUByte(), a.toUByte())
}

fun SDL3.Renderer.setDrawColor(color: SDL3.Color) {
	SDL_SetRenderDrawColor(handle, color.r, color.g, color.b, color.a)
}

/** Clear the render target with the current draw colour. */
fun SDL3.Renderer.clear() {
	SDL_RenderClear(handle)
}

/** Fill a rectangle on the render target. */
fun SDL3.Renderer.fillRect(inRect: SDL3.FRect) {
	val vRect = onStack<SDL_FRect>()
	fill(vRect, inRect)
	SDL_RenderFillRect(handle, vRect.ptr)
}

/** Draw the outline of a rectangle on the render target. */
fun SDL3.Renderer.drawRect(inRect: SDL3.FRect) {
	val vRect = onStack<SDL_FRect>()
	fill(vRect, inRect)
	SDL_RenderRect(handle, vRect.ptr)
}

/** Draw a line segment on the render target. */
fun SDL3.Renderer.drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
	SDL_RenderLine(handle, x1, y1, x2, y2)
}

/** Draw a single point on the render target. */
fun SDL3.Renderer.drawPoint(x: Float, y: Float) {
	SDL_RenderPoint(handle, x, y)
}

fun SDL3.Renderer.drawPoint(point: SDL3.FPoint) {
	SDL_RenderPoint(handle, point.x, point.y)
}

/** Set blend mode for subsequent draw calls (SDL3.BlendMode). */
fun SDL3.Renderer.setBlendMode(mode: UInt) {
	SDL_SetRenderDrawBlendMode(handle, mode)
}

/** Set the drawing scale (zoom) factor. */
fun SDL3.Renderer.setScale(scaleX: Float, scaleY: Float) {
	SDL_SetRenderScale(handle, scaleX, scaleY)
}

/** Present the rendered frame to the screen. */
fun SDL3.Renderer.present() {
	SDL_RenderPresent(handle)
}

/** Pixel dimensions of the render output (window or render target). */
fun SDL3.Renderer.outputSize(): SDL3.FPoint {
	val vWidth = onStack<IntVar>()
	val vHeight = onStack<IntVar>()
	SDL_GetRenderOutputSize(handle, vWidth.ptr, vHeight.ptr)
	return SDL3.FPoint(vWidth.value.toFloat(), vHeight.value.toFloat())
}

/** Restrict rendering to a sub-rectangle of the target (integer coords). */
fun SDL3.Renderer.setViewport(rect: SDL3.Rect) {
	val vRect = onStack<SDL_Rect>()
	fill(vRect, rect)
	SDL_SetRenderViewport(handle, vRect.ptr)
}

fun SDL3.Renderer.clearViewport() {
	SDL_SetRenderViewport(handle, null)
}

/** Restrict rendering to a clip rectangle. */
fun SDL3.Renderer.setClipRect(rect: SDL3.Rect) {
	val vRect = onStack<SDL_Rect>()
	fill(vRect, rect)
	SDL_SetRenderClipRect(handle, vRect.ptr)
}

fun SDL3.Renderer.clearClipRect() {
	SDL_SetRenderClipRect(handle, null)
}

/** Set the logical presentation size for scaling. */
fun SDL3.Renderer.setLogicalSize(w: Int, h: Int) {
	SDL_SetRenderLogicalPresentation(handle, w, h, SDL_LOGICAL_PRESENTATION_LETTERBOX)
}

/** Reset logical presentation back to native window resolution. */
fun SDL3.Renderer.clearLogicalSize() {
	SDL_SetRenderLogicalPresentation(handle, 0, 0, SDL_LOGICAL_PRESENTATION_DISABLED)
}

/** Enable or disable VSync (0 = off, 1 = on, -1 = adaptive). */
fun SDL3.Renderer.setVSync(vsync: Int) {
	SDL_SetRenderVSync(handle, vsync)
}

/** Query current VSync setting. */
fun SDL3.Renderer.getVSync(): Int {
	val vSync = onStack<IntVar>()
	SDL_GetRenderVSync(handle, vSync.ptr)
	return vSync.value
}

/** Draw a debug text string at (x, y) using the current draw color. 8x8 monospace font. */
fun SDL3.Renderer.drawDebugText(x: Float, y: Float, text: String) {
	SDL_RenderDebugText(handle, x, y, text)
}

/** Character size in pixels for debug text (8x8 monospace). */
fun SDL3.Renderer.debugTextCharSize(): Int = SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE

// ==================
// MARK: Textures
// ==================

fun SDL3.Texture.destroy() {
	SDL_DestroyTexture(handle)
}

fun SDL3.Texture.setBlendMode(mode: UInt) {
	SDL_SetTextureBlendMode(handle, mode)
}

/** Alpha multiplier applied when rendering; 0 = transparent, 255 = opaque. */
fun SDL3.Texture.setAlphaMod(alpha: Int) {
	SDL_SetTextureAlphaMod(handle, alpha.toUByte())
}

fun SDL3.Texture.setColorMod(r: Int, g: Int, b: Int) {
	SDL_SetTextureColorMod(handle, r.toUByte(), g.toUByte(), b.toUByte())
}

/** Render texture stretched to fill the entire render target. */
fun SDL3.Renderer.renderTexture(texture: SDL3.Texture) {
	SDL_RenderTexture(handle, texture.handle, null, null)
}

/** Render texture at a destination rect, sampling the full texture. */
fun SDL3.Renderer.renderTexture(texture: SDL3.Texture, dst: SDL3.FRect) {
	val vTarget = onStack<SDL_FRect>()
	fill(vTarget, dst)
	SDL_RenderTexture(handle, texture.handle, null, vTarget.ptr)
}

/** Render a cropped source region of texture at a destination rect. */
fun SDL3.Renderer.renderTexture(texture: SDL3.Texture, src: SDL3.FRect, dst: SDL3.FRect) {
	val vSource = onStack<SDL_FRect>()
	val vTarget = onStack<SDL_FRect>()
	fill(vSource, src)
	fill(vTarget, dst)
	SDL_RenderTexture(handle, texture.handle, vSource.ptr, vTarget.ptr)
}

/** Render texture with clockwise rotation in degrees and flip flags (SDL3.Flip). */
fun SDL3.Renderer.renderTextureRotated(texture: SDL3.Texture, dst: SDL3.FRect, angle: Float, flip: UInt) {
	val vTarget = onStack<SDL_FRect>()
	fill(vTarget, dst)
	SDL_RenderTextureRotated(handle, texture.handle, null, vTarget.ptr, angle.toDouble(), null, flip)
}

/** Render texture with rotation around a custom pivot point. */
fun SDL3.Renderer.renderTextureRotated(texture: SDL3.Texture, dst: SDL3.FRect, angle: Float, center: SDL3.FPoint, flip: UInt) {
	val vTarget = onStack<SDL_FRect>()
	fill(vTarget, dst)
	val vCenter = onStack<SDL_FPoint>()
	vCenter.x = center.x
	vCenter.y = center.y
	SDL_RenderTextureRotated(handle, texture.handle, null, vTarget.ptr, angle.toDouble(), vCenter.ptr, flip)
}

/** Redirect all rendering to a texture (must have TextureAccess.Target). */
fun SDL3.Renderer.setTarget(texture: SDL3.Texture) {
	SDL_SetRenderTarget(handle, texture.handle)
}

/** Reset render target to the window. */
fun SDL3.Renderer.clearTarget() {
	SDL_SetRenderTarget(handle, null)
}

/** Create a blank texture (SDL3.PixelFormat, SDL3.TextureAccess). */
fun SDL3.Renderer.createTexture(width: Int, height: Int, format: UInt, access: UInt): SDL3.Texture =
	SDL3.Texture(SDL_CreateTexture(handle, format, access, width, height) ?: error("SDL_CreateTexture failed: ${lastError()}"))

/** Load a BMP file and upload it as a static texture. */
fun SDL3.Renderer.loadTextureBmp(path: String): SDL3.Texture {
	val vSurface = SDL_LoadBMP(path) ?: error("SDL_LoadBMP failed: ${lastError()}")
	val vHandle = SDL_CreateTextureFromSurface(handle, vSurface)
	SDL_DestroySurface(vSurface)
	return SDL3.Texture(vHandle ?: error("SDL_CreateTextureFromSurface failed: ${lastError()}"))
}

// ==================
// MARK: Cursor
// ==================

fun SDL3.Cursor.destroy() {
	SDL_DestroyCursor(handle)
}

/** Set this cursor as the active cursor. */
fun SDL3.Cursor.activate() {
	SDL_SetCursor(handle)
}
