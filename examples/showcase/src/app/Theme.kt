@file:OptIn(ExperimentalForeignApi::class)

package showcase.app

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.sdl3.*

// ==================
// MARK: Colors
// ==================

/** The colors of the showcase. */
object Theme {
	val background = SDL3.Color(0x161A22FFu)
	val panel = SDL3.Color(0x232A36FFu)
	val panelEdge = SDL3.Color(0x384356FFu)
	val idle = SDL3.Color(0x3A4556FFu)
	val hover = SDL3.Color(0x4C5B73FFu)
	val accent = SDL3.Color(0xFFD479FFu)
	val text = SDL3.Color(0xE8ECF1FFu)
	val dim = SDL3.Color(0x8A94A3FFu)
	val good = SDL3.Color(0x7FD39AFFu)
	val bad = SDL3.Color(0xFF8A8AFFu)
	val passed = SDL3.Color(0x2E7D4FFFu)
	val failed = SDL3.Color(0xA83A3AFFu)

	/** The pixels of a character of SDL's built-in font, drawn at [kTextScale]. */
	const val kCharSize = 8.0f * kTextScale
}

/** Text is drawn at twice the size of SDL's built-in font, which is 8 pixels high. */
const val kTextScale = 2.0f

/** The height of the bar at the top and of the line of help at the bottom. */
const val kBarHeight = 28.0f

// ==================
// MARK: Drawing helpers
// ==================

/** Draws [inText] at ([inX], [inY]) in window pixels, [inScale] times the font's size, in [inColor]. */
fun SDL3.Renderer.text(inX: Float, inY: Float, inText: String, inColor: SDL3.Color = Theme.text, inScale: Float = kTextScale) {
	setDrawColor(inColor)
	setScale(inScale, inScale)
	drawDebugText(inX / inScale, inY / inScale, inText)
	setScale(1.0f, 1.0f)
}

/** The width in window pixels of [inText] as [text] draws it at [inScale]. */
fun textWidth(inText: String, inScale: Float = kTextScale): Float = inText.length * 8.0f * inScale

/** [inText] cut to fit [inWidth] pixels at [inScale], with ".." where it was cut. */
fun fit(inText: String, inWidth: Float, inScale: Float = kTextScale): String {
	val vRoom = (inWidth / (8.0f * inScale)).toInt()
	return if (inText.length <= vRoom) inText else inText.take(maxOf(0, vRoom - 2)) + ".."
}

/** [inText] cut between words into lines of at most [inWidth] pixels at [inScale]. */
fun wrap(inText: String, inWidth: Float, inScale: Float = kTextScale): List<String> {
	val vRoom = maxOf(1, (inWidth / (8.0f * inScale)).toInt())
	val vLines = ArrayList<String>()
	var vLine = StringBuilder()
	for (vWord in inText.split(' ')) {
		if (vLine.isNotEmpty() && vLine.length + 1 + vWord.length > vRoom) {
			vLines.add(vLine.toString())
			vLine = StringBuilder()
		}
		if (vLine.isNotEmpty()) vLine.append(' ')
		vLine.append(vWord)
	}
	if (vLine.isNotEmpty()) vLines.add(vLine.toString())
	return vLines
}

/** The size of the smaller text: status lines, help, descriptions. */
const val kSmallText = 1.5f

/** A panel: a rounded rectangle with an edge. */
fun SDL3.Renderer.panel(inRect: SDL3.FRect, inFill: SDL3.Color = Theme.panel) {
	setDrawColor(inFill)
	fillRoundedRect(inRect, 6.0f)
	setDrawColor(Theme.panelEdge)
	drawRoundedRect(inRect, 6.0f)
}

/** A color between [inFrom] and [inTo], [inT] from 0 to 1. */
fun mix(inFrom: SDL3.Color, inTo: SDL3.Color, inT: Float): SDL3.Color {
	fun part(inA: UByte, inB: UByte): UByte = (inA.toInt() + ((inB.toInt() - inA.toInt()) * inT).toInt()).coerceIn(0, 255).toUByte()
	return SDL3.Color(part(inFrom.r, inTo.r), part(inFrom.g, inTo.g), part(inFrom.b, inTo.b), part(inFrom.a, inTo.a))
}

/** A color of the rainbow: [inHue] from 0 to 1 goes once around it. */
fun hue(inHue: Float, inAlpha: Int = 255): SDL3.Color {
	val vH = (inHue - kotlin.math.floor(inHue)) * 6.0f
	val vX = 1.0f - kotlin.math.abs(vH % 2.0f - 1.0f)
	val (vR, vG, vB) = when (vH.toInt()) {
		0 -> Triple(1.0f, vX, 0.0f)
		1 -> Triple(vX, 1.0f, 0.0f)
		2 -> Triple(0.0f, 1.0f, vX)
		3 -> Triple(0.0f, vX, 1.0f)
		4 -> Triple(vX, 0.0f, 1.0f)
		else -> Triple(1.0f, 0.0f, vX)
	}
	return SDL3.Color((vR * 255).toInt().toUByte(), (vG * 255).toInt().toUByte(), (vB * 255).toInt().toUByte(), inAlpha.toUByte())
}

// ==================
// MARK: Buttons
// ==================

/** A button of a page: a label in a rounded rectangle, which [onClick] answers. */
class Button(var label: String, val bounds: SDL3.FRect, val onClick: () -> Unit) {
	var isHovered = false

	/** Set for a button that stands for a state that is on (a toggle). */
	var isOn = false

	fun contains(inX: Float, inY: Float): Boolean = bounds.contains(SDL3.FPoint(inX, inY))

	fun draw(inRenderer: SDL3.Renderer) {
		inRenderer.setDrawColor(if (isOn) Theme.passed else if (isHovered) Theme.hover else Theme.idle)
		inRenderer.fillRoundedRect(bounds, 5.0f)
		if (isHovered) {
			inRenderer.setDrawColor(Theme.text)
			inRenderer.drawRoundedRect(bounds, 5.0f)
		}
		// The label at the usual size, or smaller when it does not fit.
		val vScale = if (textWidth(label) <= bounds.w - 12.0f) kTextScale else kSmallText
		inRenderer.text(bounds.x + (bounds.w - textWidth(label, vScale)) / 2.0f, bounds.y + (bounds.h - 8.0f * vScale) / 2.0f, label, Theme.text, vScale)
	}
}

/** The buttons of a page: the mouse over them, a click on them. */
class Buttons {
	val all = ArrayList<Button>()

	fun add(inLabel: String, inX: Float, inY: Float, inWidth: Float, inHeight: Float = 36.0f, inOnClick: () -> Unit): Button =
		Button(inLabel, SDL3.FRect(inX, inY, inWidth, inHeight), inOnClick).also { all.add(it) }

	fun move(inX: Float, inY: Float) {
		for (vButton in all) vButton.isHovered = vButton.contains(inX, inY)
	}

	/** Clicks the button at ([inX], [inY]); returns false when there is none there. */
	fun click(inX: Float, inY: Float): Boolean {
		val vButton = all.firstOrNull { it.contains(inX, inY) } ?: return false
		vButton.onClick()
		return true
	}

	fun draw(inRenderer: SDL3.Renderer) {
		for (vButton in all) vButton.draw(inRenderer)
	}
}
