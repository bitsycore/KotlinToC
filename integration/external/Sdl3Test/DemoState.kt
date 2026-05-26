package test

import sdl3.*

data class MovementState(
	var left:  Boolean = false,
	var right: Boolean = false,
	var up:    Boolean = false,
	var down:  Boolean = false
)

// ══════════════════════════════════════════════════════════════
// MARK: PulseState
// ══════════════════════════════════════════════════════════════

/** Right-click glow pulse — expands and fades with additive blending. */
class PulseState(
	var active:     Boolean = false,
	var x:          Float   = 0.0f,
	var y:          Float   = 0.0f,
	var radius:     Float   = 0.0f,
	var alpha:      Float   = 0.0f,
	var r:          Int     = 255,
	var g:          Int     = 100,
	var b:          Int     = 0,
	var colorCycle: Int     = 0
) {
	/** Spawn a new pulse at (mx, my), cycling through 4 colours. */
	fun spawn(mx: Float, my: Float) {
		when (colorCycle % 4) {
			0 -> { r = 255; g = 80;  b = 0   }
			1 -> { r = 0;   g = 200; b = 255 }
			2 -> { r = 200; g = 0;   b = 255 }
			3 -> { r = 0;   g = 255; b = 100 }
		}
		colorCycle++
		x      = mx
		y      = my
		radius = 4.0f
		alpha  = 255.0f
		active = true
	}

	/** Advance the pulse animation by dt seconds. */
	fun update(dt: Float) {
		if (!active) return
		alpha  -= 200.0f * dt
		radius += 90.0f  * dt
		if (alpha <= 0.0f) active = false
	}

	/** Draw the pulse using additive blending. */
	fun render(renderer: SDL3.Renderer) {
		if (!active) return
		val a = alpha.toInt()
		renderer.setBlendMode(SDL3.BlendMode.Add)
		renderer.setDrawColor(r, g, b, a)
		renderer.fillCircle(x, y, radius)
		renderer.setDrawColor(r, g, b, a / 2)
		renderer.drawCircle(x, y, radius + 6.0f)
		renderer.setBlendMode(SDL3.BlendMode.None)
	}
}

// ══════════════════════════════════════════════════════════════
// MARK: SpriteState
// ══════════════════════════════════════════════════════════════

/**
 * Smooth-following sprite with alpha pulse, color-mod cycle, and rotation.
 * Call update() each frame, then spin(), then applyMods()+render() when drawing.
 */
class SpriteState(
	var posX:      Float = 0.0f,
	var posY:      Float = 0.0f,
	var angle:     Float = 0.0f,
	var alpha:     Float = 255.0f,
	var alphaDir:  Float = -1.0f,
	var colorR:    Int   = 255,
	var colorG:    Int   = 255,
	var colorB:    Int   = 255,
	var colorTime: Float = 0.0f
) {
	/** Lerp toward (targetX, targetY), pulse alpha, and advance the colour cycle. */
	fun update(dt: Float, targetX: Float, targetY: Float) {
		// Alpha pulse (70–255, ~1.4 s period)
		alpha += alphaDir * 160.0f * dt
		if (alpha <= 70.0f)  { alpha = 70.0f;  alphaDir =  1.0f }
		if (alpha >= 255.0f) { alpha = 255.0f; alphaDir = -1.0f }

		// Smooth follow cursor
		val lerpT = clamp(dt * 8.0f, 0.0f, 1.0f)
		posX = lerp(posX, targetX, lerpT)
		posY = lerp(posY, targetY, lerpT)

		// RGB colour cycle (hue-like tint using sin waves)
		colorTime += dt
		val ct = colorTime
		colorR = ((c.sinf(ct * 1.1f) * 0.5f + 0.5f) * 255.0f).toInt()
		colorG = ((c.sinf(ct * 1.1f + 2.094f) * 0.5f + 0.5f) * 255.0f).toInt()
		colorB = ((c.sinf(ct * 1.1f + 4.189f) * 0.5f + 0.5f) * 255.0f).toInt()
	}

	/** Rotate by spinSpeed degrees per second. */
	fun spin(dt: Float, spinSpeed: Float) {
		angle += spinSpeed * dt
	}

	/** Apply the current alpha and colour mods to the texture. */
	fun applyMods(texture: SDL3.Texture) {
		texture.setAlphaMod(alpha.toInt())
		texture.setColorMod(colorR, colorG, colorB)
	}

	/** Draw the sprite rotated at the current angle, centred on (posX, posY). */
	fun render(renderer: SDL3.Renderer, texture: SDL3.Texture) {
		val dst = SDL3.FRect(posX - 32.0f, posY - 32.0f, 64.0f, 64.0f)
		renderer.renderTextureRotated(texture, dst, angle, SDL3.Flip.None)
	}
}
