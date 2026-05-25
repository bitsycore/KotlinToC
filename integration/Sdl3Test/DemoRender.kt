package test

import sdl3.*

// ══════════════════════════════════════════════════════════════
// MARK: Box
// ══════════════════════════════════════════════════════════════

/** Fill + outline the box, and draw the expanding hover-pulse ring if active. */
fun SDL3.Renderer.renderBox(
	box:          SDL3.FRect,
	hovering:     Boolean,
	hoverPulse:   Float,
	boxColor:     SDL3.Color,
	hoverColor:   SDL3.Color,
	outlineColor: SDL3.Color
) {
	this.setDrawColor(if (hovering) hoverColor else boxColor)
	this.fillRoundedRect(box, 12.0f)
	this.setDrawColor(outlineColor)
	this.drawRoundedRect(box, 12.0f)

	if (hovering && hoverPulse > 0.0f) {
		val pulseGrow = hoverPulse * 20.0f
		val pulseA    = (255.0f * (1.0f - hoverPulse)).toInt()
		this.setDrawColor(SDL3.Color(255, 200, 50, pulseA))
		this.drawRoundedRect(box.grow(pulseGrow), 12.0f + pulseGrow)
	}
}

// ══════════════════════════════════════════════════════════════
// MARK: Leash
// ══════════════════════════════════════════════════════════════

/**
 * Draw the leash line from box centre to cursor.
 * Colour shifts green when the box moves toward the cursor, red when away.
 * Also draws the small dot at box centre and the distance-indicator ring.
 */
fun SDL3.Renderer.renderLeash(
	box:        SDL3.FRect,
	mouseX:     Float,
	mouseY:     Float,
	movState:   MovementState,
	leashColor: SDL3.Color
) {
	val center  = box.center()

	// Small dot at box centre
	this.setDrawColor(SDL3.Color(255, 255, 255, 180))
	this.drawCircle(center.x, center.y, 5.0f)

	// Distance-indicator ring (radius = distance, capped at 120)
	val dist      = center.distanceTo(SDL3.FPoint(mouseX, mouseY))
	val indRadius = if (dist > 120.0f) 120.0f else dist
	this.setDrawColor(SDL3.Color(255, 255, 255, 40))
	this.drawCircle(center.x, center.y, indRadius)

	// Leash line — colour reflects approach direction (dot product)
	val movX     = if (movState.right) 1.0f else if (movState.left) -1.0f else 0.0f
	val movY     = if (movState.down)  1.0f else if (movState.up)   -1.0f else 0.0f
	val movDir   = SDL3.FPoint(movX, movY).normalized()
	val toMouse  = SDL3.FPoint(mouseX - center.x, mouseY - center.y).normalized()
	val approach = movDir.dot(toMouse)
	val color    = if (approach > 0.3f) SDL3.Color(50, 220, 100, 160)
	               else if (approach < -0.3f) SDL3.Color(200, 80, 50, 160)
	               else leashColor
	this.setDrawColor(color)
	this.drawLine(center.x, center.y, mouseX, mouseY)
}

// ══════════════════════════════════════════════════════════════
// MARK: Direction arrow
// ══════════════════════════════════════════════════════════════

/** Gradient triangle pointing from box centre toward the cursor (only when far enough). */
fun SDL3.Renderer.renderDirectionArrow(
	box:    SDL3.FRect,
	mouseX: Float,
	mouseY: Float
) {
	val center = box.center()
	val dx     = mouseX - center.x
	val dy     = mouseY - center.y
	val dist   = center.distanceTo(SDL3.FPoint(mouseX, mouseY))
	if (dist <= 30.0f) return

	val dir   = SDL3.FPoint(dx, dy).normalized()
	val nx    = dir.x
	val ny    = dir.y
	val reach = if (dist < 100.0f) dist - 20.0f else 80.0f
	val tipX  = center.x + nx * reach
	val tipY  = center.y + ny * reach
	val baseX = center.x + nx * 16.0f
	val baseY = center.y + ny * 16.0f
	val perpX = -ny * 10.0f
	val perpY =  nx * 10.0f

	this.setBlendMode(SDL3.BlendMode.Blend)
	this.fillTriangle(
		tipX,           tipY,           1.0f, 1.0f, 1.0f, 0.9f,
		baseX + perpX,  baseY + perpY,  0.2f, 0.5f, 1.0f, 0.0f,
		baseX - perpX,  baseY - perpY,  0.2f, 0.5f, 1.0f, 0.0f
	)
	this.setBlendMode(SDL3.BlendMode.None)
}

// ══════════════════════════════════════════════════════════════
// MARK: Crosshair
// ══════════════════════════════════════════════════════════════

/** Cross-hair lines and ring at the cursor position. */
fun SDL3.Renderer.renderCrosshair(mouseX: Float, mouseY: Float, color: SDL3.Color) {
	this.setDrawColor(color)
	this.drawLine(mouseX - 12.0f, mouseY,         mouseX + 12.0f, mouseY)
	this.drawLine(mouseX,         mouseY - 12.0f, mouseX,         mouseY + 12.0f)
	this.drawCircle(mouseX, mouseY, 8.0f)
}

// ══════════════════════════════════════════════════════════════
// MARK: Atlas HUD
// ══════════════════════════════════════════════════════════════

/** Bottom-left tile strip — crops the current tile from the atlas and draws it. */
fun SDL3.Renderer.renderAtlasHud(atlas: SDL3.Texture, tileIdx: Int, ws: SDL3.FPoint) {
	val src = SDL3.FRect(tileIdx.toFloat() * 32.0f, 0.0f, 32.0f, 32.0f)
	val dst = SDL3.FRect(10.0f, ws.y - 58.0f, 48.0f, 48.0f)
	this.renderTexture(atlas, src, dst)
	this.setDrawColor(SDL3.Color(255, 255, 255, 80))
	this.drawRect(dst)
}

// ══════════════════════════════════════════════════════════════
// MARK: Minimap
// ══════════════════════════════════════════════════════════════

/** Top-right corner minimap using setViewport. Shows box, cursor, and sprite. */
fun SDL3.Renderer.renderMinimap(
	ws:         SDL3.FPoint,
	box:        SDL3.FRect,
	spritePosX: Float,
	spritePosY: Float,
	mouseX:     Float,
	mouseY:     Float,
	hovering:   Boolean,
	boxColor:   SDL3.Color,
	hoverColor: SDL3.Color
) {
	val mmW   = 150
	val mmH   = 100
	val mmVpX = ws.x.toInt() - mmW - 10
	this.setViewport(SDL3.Rect(mmVpX, 10, mmW, mmH))

	// Background
	this.setDrawColor(15, 15, 35, 255)
	this.fillRect(SDL3.FRect(0.0f, 0.0f, mmW.toFloat(), mmH.toFloat()))
	this.setDrawColor(40, 40, 70, 255)
	this.drawRect(SDL3.FRect(0.0f, 0.0f, mmW.toFloat(), mmH.toFloat()))

	// Scaled elements
	val scaleX = mmW.toFloat() / ws.x
	val scaleY = mmH.toFloat() / ws.y
	this.setDrawColor(if (hovering) hoverColor else boxColor)
	this.fillRect(SDL3.FRect(box.x * scaleX, box.y * scaleY, box.w * scaleX, box.h * scaleY))
	this.setDrawColor(SDL3.Color(255, 80, 80, 220))
	this.drawCircle(mouseX * scaleX, mouseY * scaleY, 3.0f)
	this.setDrawColor(SDL3.Color(100, 200, 100, 180))
	this.drawCircle(spritePosX * scaleX, spritePosY * scaleY, 2.0f)

	this.clearViewport()
}
