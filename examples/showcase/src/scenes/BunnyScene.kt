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
import showcase.gfx.SpriteBatch
import showcase.gfx.bunnyTexture
import kotlin.random.Random

/**
 * The bunny test: as many sprites as asked for, falling and bouncing off the edges of the window.
 * They are arrays of numbers (x, y and the speeds), not objects: a million is 16 MB. Moving them is
 * shared between threads (ktc2.concurrent), each with its part of the arrays; drawing them goes
 * through [SpriteBatch], 16384 at a time, in eight colors.
 */
class BunnyScene : Scene {
	override val title = "Bunnies"
	override val summary = "A million sprites bouncing, moved by threads, drawn in batches"
	override val help = "Space/click +100k   Up +1M   Down -half   C clear   T threads"

	/** The app, from its global: a field of its own would close a cycle (the app holds its pages). */
	private val mApp: App get() = gApp
	private var mBatch: SpriteBatch? = null
	private val mRandom = Random(42)

	private var mX = FloatArray(0)
	private var mY = FloatArray(0)
	private var mVx = FloatArray(0)
	private var mVy = FloatArray(0)
	private var mCount = 0

	/** How many threads move the bunnies; 1 is the thread that draws. */
	private var mThreads = 8
	private var mUpdateMs = 0.0f
	private var mDrawMs = 0.0f
	private var mMostShown = 0

	private val kWidth = 16.0f
	private val kHeight = 16.0f
	private val kGravity = 900.0f

	/** The eight colors of the bunnies, one for each eighth of them. */
	private val kTints = arrayOf(
		floatArrayOf(1.0f, 1.0f, 1.0f), floatArrayOf(1.0f, 0.6f, 0.6f), floatArrayOf(0.6f, 1.0f, 0.6f), floatArrayOf(0.6f, 0.7f, 1.0f),
		floatArrayOf(1.0f, 1.0f, 0.5f), floatArrayOf(1.0f, 0.6f, 1.0f), floatArrayOf(0.5f, 1.0f, 1.0f), floatArrayOf(1.0f, 0.8f, 0.5f),
	)

	override fun enter(app: App) {
		if (mBatch == null) mBatch = SpriteBatch(app.renderer, app.renderer.bunnyTexture())
		if (mCount == 0) add(100_000)
	}

	/** Adds [inHow] bunnies at the top left, thrown to the right. */
	private fun add(inHow: Int) {
		val vNeeded = mCount + inHow
		if (vNeeded > mX.size) {
			val vSize = maxOf(vNeeded, mX.size * 2)
			mX = mX.copyOf(vSize)
			mY = mY.copyOf(vSize)
			mVx = mVx.copyOf(vSize)
			mVy = mVy.copyOf(vSize)
		}
		for (vIndex in mCount until vNeeded) {
			mX[vIndex] = mRandom.nextFloat() * 40.0f
			mY[vIndex] = 40.0f + mRandom.nextFloat() * 40.0f
			mVx[vIndex] = 100.0f + mRandom.nextFloat() * 400.0f
			mVy[vIndex] = mRandom.nextFloat() * 400.0f - 200.0f
		}
		mCount = vNeeded
		mMostShown = maxOf(mMostShown, mCount)
	}

	override fun event(event: SDL_Event) {
		when (event.kind) {
			SDL3.Event.KeyDown -> when (event.key.code) {
				SDL3.Scancode.Space -> add(100_000)
				SDL3.Scancode.Up -> add(1_000_000)
				SDL3.Scancode.Down -> mCount /= 2
				SDL3.Scancode.C -> mCount = 0
				SDL3.Scancode.T -> mThreads = if (mThreads == 1) 8 else 1
				else -> {}
			}
			SDL3.Event.MouseButtonDown -> add(100_000)
			else -> {}
		}
	}

	/** Moves the bunnies from [inFrom] to [inTo] (not included) by [inSeconds]. */
	private fun move(inFrom: Int, inTo: Int, inSeconds: Float, inRight: Float, inBottom: Float, inSeed: Int) {
		val vX = mX
		val vY = mY
		val vVx = mVx
		val vVy = mVy
		// A generator of each thread's own: a shared one would be a point every thread waits on.
		var vState = inSeed * 1103515245 + 12345
		for (vIndex in inFrom until inTo) {
			var vSpeedY = vVy[vIndex] + kGravity * inSeconds
			var vPlaceX = vX[vIndex] + vVx[vIndex] * inSeconds
			var vPlaceY = vY[vIndex] + vSpeedY * inSeconds
			if (vPlaceX > inRight) {
				vVx[vIndex] = -vVx[vIndex]
				vPlaceX = inRight
			} else if (vPlaceX < 0.0f) {
				vVx[vIndex] = -vVx[vIndex]
				vPlaceX = 0.0f
			}
			if (vPlaceY > inBottom) {
				vState = vState * 1103515245 + 12345
				// Thrown up again, more or less hard.
				vSpeedY = -(500.0f + ((vState ushr 16) and 1023) * 0.6f)
				vPlaceY = inBottom
			} else if (vPlaceY < 28.0f) {
				vSpeedY = 0.0f
				vPlaceY = 28.0f
			}
			vVy[vIndex] = vSpeedY
			vX[vIndex] = vPlaceX
			vY[vIndex] = vPlaceY
		}
	}

	override fun update(seconds: Float) {
		val vStart = Clock.now()
		val vRight = mApp.width - kWidth
		val vBottom = mApp.height - 28.0f - kHeight
		if (mThreads == 1 || mCount < 50_000) {
			move(0, mCount, seconds, vRight, vBottom, 1)
		} else {
			val vShare = (mCount + mThreads - 1) / mThreads
			val vWorkers = ArrayList<Thread>()
			for (vPart in 1 until mThreads) {
				val vFrom = vPart * vShare
				val vTo = minOf(mCount, vFrom + vShare)
				if (vFrom < vTo) vWorkers.add(thread { move(vFrom, vTo, seconds, vRight, vBottom, vPart + 1) })
			}
			// The first part on this thread, while the others work.
			move(0, minOf(mCount, vShare), seconds, vRight, vBottom, 1)
			for (vWorker in vWorkers) vWorker.join()
		}
		mUpdateMs = mUpdateMs * 0.9f + ((Clock.now() - vStart) * 1000.0).toFloat() * 0.1f
	}

	override fun draw(renderer: SDL3.Renderer) {
		val vStart = Clock.now()
		val vBatch = mBatch ?: return
		vBatch.begin()
		val vEighth = (mCount + 7) / 8
		for (vTint in 0 until 8) {
			val vColor = kTints[vTint]
			vBatch.color(vColor[0], vColor[1], vColor[2], 1.0f)
			val vTo = minOf(mCount, (vTint + 1) * vEighth)
			for (vIndex in vTint * vEighth until vTo) vBatch.add(mX[vIndex], mY[vIndex], kWidth, kHeight)
		}
		vBatch.flush()
		mDrawMs = mDrawMs * 0.9f + ((Clock.now() - vStart) * 1000.0).toFloat() * 0.1f
	}

	override fun status(): String =
		"${mCount} bunnies   move ${(mUpdateMs * 10).toInt() / 10.0f} ms on ${mThreads} thread${if (mThreads > 1) "s" else ""}   " +
			"draw ${(mDrawMs * 10).toInt() / 10.0f} ms in ${mBatch?.calls ?: 0} calls"

	override fun autoStep(frame: Int): Boolean {
		when (frame) {
			30 -> add(400_000)
			60 -> add(500_000)
			120 -> mThreads = 1
			150 -> mThreads = 8
		}
		return frame < 200
	}

	override fun report(): String = "up to $mMostShown bunnies, moved in ${(mUpdateMs * 10).toInt() / 10.0f} ms, drawn in ${(mDrawMs * 10).toInt() / 10.0f} ms"
}
