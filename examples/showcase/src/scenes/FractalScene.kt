@file:OptIn(ExperimentalForeignApi::class)

package showcase.scenes

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.concurrent.Thread
import ktc2.concurrent.thread
import ktc2.sdl3.*
import sdl3.SDL_Event
import showcase.app.App
import showcase.app.gApp
import showcase.app.Clock
import showcase.app.Scene
import showcase.app.hue
import showcase.gfx.streamingTexture
import showcase.gfx.upload
import kotlin.math.ln

/**
 * The Mandelbrot set, computed again whenever the view moves: each of several threads takes every
 * n-th row of the picture into one array of pixels, which goes to a streaming texture. The wheel
 * zooms where the mouse is, a drag moves the view, Z zooms in by itself.
 */
class FractalScene : Scene {
	override val title = "Fractal"
	override val summary = "The Mandelbrot set, computed by threads into a texture"
	override val help = "Wheel zoom   Drag move   Z auto zoom   +/- depth   T threads   R reset"

	/** The app, from its global: a field of its own would close a cycle (the app holds its pages). */
	private val mApp: App get() = gApp
	private var mTexture: SDL3.Texture? = null
	private var mWidth = 0
	private var mHeight = 0
	private var mPixels = IntArray(0)

	/** The palette: a color for each step of the escape, smooth. */
	private val mPalette = IntArray(1024)

	private var mCenterX = -0.6
	private var mCenterY = 0.0
	private var mScale = 3.2 / 1280.0
	private var mDepth = 400
	private var mThreads = 8
	private var mIsAutoZoom = false
	private var mIsDirty = true
	private var mRenderMs = 0.0f
	private var mDragging = false
	private var mDragX = 0.0f
	private var mDragY = 0.0f
	private var mFrames = 0

	override fun enter(app: App) {
		if (mTexture == null) {
			mWidth = app.width
			mHeight = app.height - 56
			mPixels = IntArray(mWidth * mHeight)
			mTexture = app.renderer.streamingTexture(mWidth, mHeight)
			for (vIndex in mPalette.indices) {
				val vColor = hue(0.62f + vIndex / 256.0f)
				val vLight = (vIndex / 40.0f).coerceAtMost(1.0f)
				mPalette[vIndex] = (255 shl 24) or ((vColor.b.toInt() * vLight).toInt() shl 16) or ((vColor.g.toInt() * vLight).toInt() shl 8) or (vColor.r.toInt() * vLight).toInt()
			}
		}
		mIsDirty = true
	}

	override fun event(event: SDL_Event) {
		when (event.kind) {
			SDL3.Event.MouseWheel -> zoomAt(mApp.width / 2.0f, mApp.height / 2.0f, if (event.wheel.y > 0) 0.8 else 1.25)
			SDL3.Event.MouseButtonDown -> {
				mDragging = true
				mDragX = event.button.x
				mDragY = event.button.y
			}
			SDL3.Event.MouseButtonUp -> mDragging = false
			SDL3.Event.MouseMotion -> if (mDragging) {
				mCenterX -= (event.motion.x - mDragX) * mScale
				mCenterY -= (event.motion.y - mDragY) * mScale
				mDragX = event.motion.x
				mDragY = event.motion.y
				mIsDirty = true
			}
			SDL3.Event.KeyDown -> when (event.key.code) {
				SDL3.Scancode.Z -> mIsAutoZoom = !mIsAutoZoom
				SDL3.Scancode.Equals, SDL3.Scancode.KpPlus -> { mDepth = minOf(5000, mDepth * 2); mIsDirty = true }
				SDL3.Scancode.Minus, SDL3.Scancode.KpMinus -> { mDepth = maxOf(50, mDepth / 2); mIsDirty = true }
				SDL3.Scancode.T -> { mThreads = if (mThreads == 1) 8 else 1; mIsDirty = true }
				SDL3.Scancode.R -> { mCenterX = -0.6; mCenterY = 0.0; mScale = 3.2 / 1280.0; mIsDirty = true }
				else -> {}
			}
			else -> {}
		}
	}

	private fun zoomAt(inX: Float, inY: Float, inFactor: Double) {
		val vX = mCenterX + (inX - mWidth / 2.0) * mScale
		val vY = mCenterY + (inY - 28.0 - mHeight / 2.0) * mScale
		mScale *= inFactor
		mCenterX = vX - (inX - mWidth / 2.0) * mScale
		mCenterY = vY - (inY - 28.0 - mHeight / 2.0) * mScale
		mIsDirty = true
	}

	/** Computes the rows [inFirst], [inFirst] + [inStep]... of the picture. */
	private fun rows(inFirst: Int, inStep: Int) {
		val vPixels = mPixels
		val vDepth = mDepth
		val vLeft = mCenterX - mWidth / 2.0 * mScale
		val vTop = mCenterY - mHeight / 2.0 * mScale
		var vRow = inFirst
		while (vRow < mHeight) {
			val vCi = vTop + vRow * mScale
			for (vColumn in 0 until mWidth) {
				val vCr = vLeft + vColumn * mScale
				var vZr = 0.0
				var vZi = 0.0
				var vSteps = 0
				while (vSteps < vDepth && vZr * vZr + vZi * vZi < 16.0) {
					val vNext = vZr * vZr - vZi * vZi + vCr
					vZi = 2.0 * vZr * vZi + vCi
					vZr = vNext
					vSteps++
				}
				vPixels[vRow * mWidth + vColumn] = if (vSteps >= vDepth) (255 shl 24) else {
					// The step at which it left, made continuous by how far it went.
					val vSmooth = vSteps + 1 - ln(ln(vZr * vZr + vZi * vZi) / 2.0) / ln(2.0)
					mPalette[((vSmooth * 4.0).toInt() and 1023)]
				}
			}
			vRow += inStep
		}
	}

	override fun update(seconds: Float) {
		if (mIsAutoZoom) {
			// Toward a place of the edge that stays interesting for a long way.
			mCenterX += (-0.743643887037151 - mCenterX) * minOf(1.0, seconds * 2.0)
			mCenterY += (0.131825904205330 - mCenterY) * minOf(1.0, seconds * 2.0)
			mScale *= 1.0 - minOf(0.5, seconds * 0.8)
			if (mScale < 1e-13) mScale = 3.2 / 1280.0
			mIsDirty = true
		}
		if (!mIsDirty) return
		mIsDirty = false
		val vStart = Clock.now()
		val vWorkers = ArrayList<Thread>()
		for (vPart in 1 until mThreads) vWorkers.add(thread { rows(vPart, mThreads) })
		rows(0, mThreads)
		for (vWorker in vWorkers) vWorker.join()
		mTexture?.upload(mPixels, mWidth)
		mFrames++
		mRenderMs = mRenderMs * 0.8f + ((Clock.now() - vStart) * 1000.0).toFloat() * 0.2f
	}

	override fun draw(renderer: SDL3.Renderer) {
		val vTexture = mTexture ?: return
		renderer.renderTexture(vTexture, SDL3.FRect(0.0f, 28.0f, mWidth.toFloat(), mHeight.toFloat()))
	}

	override fun status(): String =
		"zoom x${(3.2 / 1280.0 / mScale).toLong()}   depth $mDepth   ${(mRenderMs * 10).toInt() / 10.0f} ms on $mThreads thread${if (mThreads > 1) "s" else ""}"

	override fun autoStep(frame: Int): Boolean {
		if (frame == 1) mIsAutoZoom = true
		if (frame == 120) mThreads = 1
		if (frame == 150) mThreads = 8
		if (frame == 199) mIsAutoZoom = false
		return frame < 200
	}

	override fun report(): String = "$mFrames pictures computed, the last in ${(mRenderMs * 10).toInt() / 10.0f} ms, zoom x${(3.2 / 1280.0 / mScale).toLong()}"
}
