@file:OptIn(ExperimentalForeignApi::class)

package showcase.scenes

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.sdl3.*
import sdl3.SDL_Event
import showcase.app.App
import showcase.app.gApp
import showcase.app.Buttons
import showcase.app.Scene
import showcase.app.Theme
import showcase.app.hue
import showcase.app.panel
import showcase.app.text
import showcase.app.textWidth
import showcase.app.kSmallText
import showcase.app.wrap
import kotlin.math.sin

/** The first page: a card for each of the others, over a field of drifting stars. */
class MenuScene : Scene {
	override val title = "ktc2 showcase"
	override val summary = "The pages of the showcase"
	override val help = ""

	/** The app, from its global: a field of its own would close a cycle (the app holds its pages). */
	private val mApp: App get() = gApp
	private val mButtons = Buttons()
	private var mTime = 0.0f

	/** The stars of the background: x, y, speed of each. */
	private val mStars = FloatArray(300 * 3)

	override fun enter(app: App) {
		if (mButtons.all.isNotEmpty()) return
		val vRandom = kotlin.random.Random(7)
		for (vIndex in 0 until mStars.size / 3) {
			mStars[vIndex * 3] = vRandom.nextFloat() * app.width
			mStars[vIndex * 3 + 1] = vRandom.nextFloat() * app.height
			mStars[vIndex * 3 + 2] = 10.0f + vRandom.nextFloat() * 60.0f
		}
		val vPages = app.scenes.drop(1)
		val vColumns = 2
		val vWidth = 560.0f
		val vHeight = 88.0f
		val vLeft = (app.width - vColumns * vWidth - 20.0f) / 2.0f
		vPages.forEachIndexed { vIndex, vScene ->
			val vX = vLeft + (vIndex % vColumns) * (vWidth + 20.0f)
			val vY = 150.0f + (vIndex / vColumns) * (vHeight + 18.0f)
			mButtons.add("", vX, vY, vWidth, vHeight) { app.show(vScene) }
		}
	}

	override fun event(event: SDL_Event) {
		when (event.kind) {
			SDL3.Event.MouseMotion -> mButtons.move(event.motion.x, event.motion.y)
			SDL3.Event.MouseButtonDown -> if (event.button.pressed == SDL3.MouseButton.Left) mButtons.click(event.button.x, event.button.y)
			else -> {}
		}
	}

	override fun update(seconds: Float) {
		mTime += seconds
		for (vIndex in 0 until mStars.size / 3) {
			var vX = mStars[vIndex * 3] - mStars[vIndex * 3 + 2] * seconds
			if (vX < 0.0f) vX += mApp.width
			mStars[vIndex * 3] = vX
		}
	}

	override fun draw(renderer: SDL3.Renderer) {
		for (vIndex in 0 until mStars.size / 3) {
			val vBright = (mStars[vIndex * 3 + 2] * 3.0f).toInt().coerceIn(40, 255).toUByte()
			renderer.setDrawColor(SDL3.Color(vBright, vBright, vBright, 255u))
			renderer.drawPoint(mStars[vIndex * 3], mStars[vIndex * 3 + 1])
		}
		val vTitle = "ktc2: Kotlin to C"
		// Four times the font's size, each letter of its own color and height.
		val vScale = 4.0f
		val vLeft = (mApp.width - vTitle.length * 8.0f * vScale) / 2.0f
		renderer.setScale(vScale, vScale)
		vTitle.forEachIndexed { vIndex, vChar ->
			renderer.setDrawColor(hue(mTime * 0.15f + vIndex * 0.04f))
			val vY = 50.0f + sin(mTime * 3.0f + vIndex * 0.5f) * 8.0f
			renderer.drawDebugText((vLeft + vIndex * 8.0f * vScale) / vScale, vY / vScale, vChar.toString())
		}
		renderer.setScale(1.0f, 1.0f)
		val vLine = "One program: every page Kotlin compiled to C, reference counted, no collector"
		renderer.text((mApp.width - textWidth(vLine, kSmallText)) / 2.0f, 112.0f, vLine, Theme.dim, kSmallText)
		val vPages = mApp.scenes.drop(1)
		mButtons.all.forEachIndexed { vIndex, vButton ->
			val vScene = vPages[vIndex]
			renderer.panel(vButton.bounds, if (vButton.isHovered) Theme.hover else Theme.panel)
			renderer.text(vButton.bounds.x + 16.0f, vButton.bounds.y + 16.0f, "${vIndex + 1}  ${vScene.title}", Theme.accent)
			for ((vRow, vText) in wrap(vScene.summary, vButton.bounds.w - 32.0f, kSmallText).take(2).withIndex()) {
				renderer.text(vButton.bounds.x + 16.0f, vButton.bounds.y + 46.0f + vRow * 16.0f, vText, Theme.text, kSmallText)
			}
		}
	}

	override fun autoStep(frame: Int): Boolean = frame < 90
}
