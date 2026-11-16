@file:OptIn(ExperimentalForeignApi::class)

package showcase.scenes

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.arena
import ktc2.sdl3.*
import sdl3.SDL_Event
import showcase.app.App
import showcase.app.gApp
import showcase.app.Clock
import showcase.app.Scene
import showcase.app.hue
import showcase.gfx.SpriteBatch
import showcase.gfx.paintedTexture
import showcase.gfx.rgba
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Balls that fall, collide and roll, a few thousand of them. Who touches whom is found through a
 * grid: each frame every ball goes into the bucket of its cell (a HashMap of ArrayLists), and a
 * ball is tested against the balls of its cell and the cells around it. That grid lives for one
 * frame: with the arena on (A), it is made inside `arena { }` and given back in one go.
 */
class PhysicsScene : Scene {
	override val title = "Physics"
	override val summary = "Thousands of colliding balls, found through a grid made in an arena"
	override val help = "Hold the mouse to pull   Space +500   C clear   A arena   G gravity"

	/** The app, from its global: a field of its own would close a cycle (the app holds its pages). */
	private val mApp: App get() = gApp
	private var mBatch: SpriteBatch? = null
	private val mRandom = Random(11)
	private var mX = FloatArray(0)
	private var mY = FloatArray(0)
	private var mVx = FloatArray(0)
	private var mVy = FloatArray(0)
	private var mRadius = FloatArray(0)
	private var mCount = 0

	private var mUsesArena = true
	private var mHasGravity = true
	private var mPulling = false
	private var mPullX = 0.0f
	private var mPullY = 0.0f
	private var mStepMs = 0.0f
	private var mContacts = 0
	private var mBuckets = 0

	private val kCell = 24.0f

	override fun enter(app: App) {
		if (mBatch == null) {
			// A white disc with a soft edge, tinted for each speed.
			mBatch = SpriteBatch(app.renderer, app.renderer.paintedTexture(32, 32, inSmooth = true) { vX, vY ->
				val vDx = vX + 0.5f - 16.0f
				val vDy = vY + 0.5f - 16.0f
				val vEdge = (16.0f - sqrt(vDx * vDx + vDy * vDy)).coerceIn(0.0f, 1.0f)
				val vShade = (255 - (vDx + vDy + 32.0f) * 2.0f).toInt()
				rgba(vShade, vShade, vShade, (vEdge * 255).toInt())
			})
		}
		if (mCount == 0) add(2500)
	}

	private fun add(inHow: Int) {
		val vNeeded = mCount + inHow
		if (vNeeded > mX.size) {
			val vSize = maxOf(vNeeded, mX.size * 2)
			mX = mX.copyOf(vSize); mY = mY.copyOf(vSize)
			mVx = mVx.copyOf(vSize); mVy = mVy.copyOf(vSize)
			mRadius = mRadius.copyOf(vSize)
		}
		for (vIndex in mCount until vNeeded) {
			mRadius[vIndex] = 4.0f + mRandom.nextFloat() * 7.0f
			mX[vIndex] = 40.0f + mRandom.nextFloat() * (mApp.width - 80.0f)
			mY[vIndex] = 40.0f + mRandom.nextFloat() * 200.0f
			mVx[vIndex] = mRandom.nextFloat() * 200.0f - 100.0f
			mVy[vIndex] = 0.0f
		}
		mCount = vNeeded
	}

	override fun event(event: SDL_Event) {
		when (event.kind) {
			SDL3.Event.KeyDown -> when (event.key.code) {
				SDL3.Scancode.Space -> add(500)
				SDL3.Scancode.C -> mCount = 0
				SDL3.Scancode.A -> mUsesArena = !mUsesArena
				SDL3.Scancode.G -> mHasGravity = !mHasGravity
				else -> {}
			}
			SDL3.Event.MouseButtonDown -> {
				mPulling = true
				mPullX = event.button.x
				mPullY = event.button.y
			}
			SDL3.Event.MouseMotion -> {
				mPullX = event.motion.x
				mPullY = event.motion.y
			}
			SDL3.Event.MouseButtonUp -> mPulling = false
			else -> {}
		}
	}

	private fun cellOf(inX: Float, inY: Float): Int = (inY / kCell).toInt() * 4096 + (inX / kCell).toInt()

	/** Pushes apart the balls that overlap, using a grid made for this step; returns how many touched. */
	private fun collide(): Int {
		val vGrid = HashMap<Int, ArrayList<Int>>()
		for (vIndex in 0 until mCount) vGrid.getOrPut(cellOf(mX[vIndex], mY[vIndex])) { ArrayList(4) }.add(vIndex)
		mBuckets = vGrid.size
		var vContacts = 0
		for (vIndex in 0 until mCount) {
			val vCellX = (mX[vIndex] / kCell).toInt()
			val vCellY = (mY[vIndex] / kCell).toInt()
			for (vDy in -1..1) for (vDx in -1..1) {
				val vBucket = vGrid[(vCellY + vDy) * 4096 + vCellX + vDx] ?: continue
				for (vOther in vBucket) {
					if (vOther <= vIndex) continue
					val vX = mX[vOther] - mX[vIndex]
					val vY = mY[vOther] - mY[vIndex]
					val vReach = mRadius[vIndex] + mRadius[vOther]
					val vSquare = vX * vX + vY * vY
					if (vSquare >= vReach * vReach || vSquare < 0.0001f) continue
					vContacts++
					val vDistance = sqrt(vSquare)
					val vNx = vX / vDistance
					val vNy = vY / vDistance
					// Each moves out by half of the overlap, the lighter more.
					val vOverlap = (vReach - vDistance) * 0.5f
					mX[vIndex] -= vNx * vOverlap; mY[vIndex] -= vNy * vOverlap
					mX[vOther] += vNx * vOverlap; mY[vOther] += vNy * vOverlap
					val vApproach = (mVx[vOther] - mVx[vIndex]) * vNx + (mVy[vOther] - mVy[vIndex]) * vNy
					if (vApproach < 0.0f) {
						val vImpulse = -vApproach * 0.9f
						mVx[vIndex] -= vNx * vImpulse; mVy[vIndex] -= vNy * vImpulse
						mVx[vOther] += vNx * vImpulse; mVy[vOther] += vNy * vImpulse
					}
				}
			}
		}
		return vContacts
	}

	override fun update(seconds: Float) {
		val vStart = Clock.now()
		val vSteps = 2
		val vDt = seconds / vSteps
		val vRight = mApp.width.toFloat()
		val vBottom = mApp.height - 28.0f
		repeat(vSteps) {
			for (vIndex in 0 until mCount) {
				if (mHasGravity) mVy[vIndex] += 700.0f * vDt
				if (mPulling) {
					val vX = mPullX - mX[vIndex]
					val vY = mPullY - mY[vIndex]
					val vLength = sqrt(vX * vX + vY * vY) + 20.0f
					mVx[vIndex] += vX / vLength * 1800.0f * vDt
					mVy[vIndex] += vY / vLength * 1800.0f * vDt
				}
				mVx[vIndex] *= 0.999f
				mX[vIndex] += mVx[vIndex] * vDt
				mY[vIndex] += mVy[vIndex] * vDt
				val vR = mRadius[vIndex]
				if (mX[vIndex] < vR) { mX[vIndex] = vR; mVx[vIndex] = -mVx[vIndex] * 0.7f }
				if (mX[vIndex] > vRight - vR) { mX[vIndex] = vRight - vR; mVx[vIndex] = -mVx[vIndex] * 0.7f }
				if (mY[vIndex] < 28.0f + vR) { mY[vIndex] = 28.0f + vR; mVy[vIndex] = -mVy[vIndex] * 0.7f }
				if (mY[vIndex] > vBottom - vR) { mY[vIndex] = vBottom - vR; mVy[vIndex] = -mVy[vIndex] * 0.5f }
			}
			// The grid and its buckets are of this step only: in an arena, all of them go back at once.
			mContacts = if (mUsesArena) arena(1 shl 18) { collide() } else collide()
		}
		mStepMs = mStepMs * 0.9f + ((Clock.now() - vStart) * 1000.0).toFloat() * 0.1f
	}

	override fun draw(renderer: SDL3.Renderer) {
		// By color: twelve steps of speed, from blue (still) to red (fast), each one call of the batch.
		val vBatch = mBatch ?: return
		vBatch.begin()
		for (vStep in 0 until 12) {
			val vColor = hue(0.6f - vStep * 0.05f)
			vBatch.color(vColor.r.toInt() / 255.0f, vColor.g.toInt() / 255.0f, vColor.b.toInt() / 255.0f, 1.0f)
			for (vIndex in 0 until mCount) {
				val vSpeed = sqrt(mVx[vIndex] * mVx[vIndex] + mVy[vIndex] * mVy[vIndex])
				if ((vSpeed / 75.0f).toInt().coerceAtMost(11) != vStep) continue
				val vR = mRadius[vIndex]
				vBatch.add(mX[vIndex] - vR, mY[vIndex] - vR, vR * 2.0f, vR * 2.0f)
			}
		}
		vBatch.flush()
		if (mPulling) {
			renderer.setDrawColor(SDL3.Color(0xFFFFFF60u))
			renderer.drawCircle(mPullX, mPullY, 30.0f)
		}
	}

	override fun status(): String =
		"$mCount balls   $mContacts contacts   $mBuckets buckets   step ${(mStepMs * 10).toInt() / 10.0f} ms   arena ${if (mUsesArena) "on" else "off"}"

	override fun autoStep(frame: Int): Boolean {
		when (frame) {
			40 -> { mPulling = true; mPullX = mApp.width / 2.0f; mPullY = mApp.height / 2.0f }
			100 -> { mPulling = false; add(1500) }
			150 -> mUsesArena = false
			180 -> mUsesArena = true
		}
		return frame < 220
	}

	override fun report(): String = "$mCount balls, $mContacts contacts in the last step, ${(mStepMs * 10).toInt() / 10.0f} ms a frame"
}
