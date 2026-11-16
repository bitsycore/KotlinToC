@file:OptIn(ExperimentalForeignApi::class)

package showcase.scenes

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.arena
import ktc2.sdl3.*
import sdl3.SDL_Event
import sdl3.SDL_SetTextureBlendMode
import sdl3.SDL_BLENDMODE_ADD
import showcase.app.App
import showcase.app.gApp
import showcase.app.Clock
import showcase.app.Scene
import showcase.app.hue
import showcase.gfx.SpriteBatch
import showcase.gfx.glowTexture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A spark of a burst: an object of its own, made by the hundred and dropped when it fades. */
class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val size: Float)

/** What flies up and bursts: a rocket, then its sparks, all of one color, which fade together. */
class Firework(var x: Float, var y: Float, var vy: Float, val color: SDL3.Color, val sparks: Int) {
	val burst = ArrayList<Spark>()
	var age = 0.0f
	val hasBurst: Boolean get() = burst.isNotEmpty() || age > 0.0f
	val isOver: Boolean get() = age > 2.6f
}

/**
 * Fireworks: rockets that go up and burst into sparks that fall and fade. Every rocket and every
 * spark is an object, made and freed as they come and go: a few hundred thousand a second, which
 * is the work of ktc2's allocator (its pools of small objects) and of the counts of references.
 * With the arena on (A), the sparks of a burst are made inside `arena { }`: they share blocks of
 * memory, which go when the last spark of a block is freed. They are born and die together.
 */
class ParticlesScene : Scene {
	override val title = "Particles"
	override val summary = "Fireworks: hundreds of thousands of objects made and freed a second"
	override val help = "Click a burst   Space a volley   +/- rockets   A arena"

	/** The app, from its global: a field of its own would close a cycle (the app holds its pages). */
	private val mApp: App get() = gApp
	private var mBatch: SpriteBatch? = null
	private val mFireworks = ArrayList<Firework>()
	private val mRandom = Random(3)
	private var mUntilNext = 0.0f
	private var mRate = 6.0f
	private var mSparksMade = 0L
	private var mSparksPerSecond = 0
	private var mCountedSince = 0.0
	private var mCountedSparks = 0L
	private var mMostAlive = 0

	/** Whether the sparks of a burst are made in an arena of their own (ktc2.arena). */
	private var mUsesArena = true
	private var mUpdateMs = 0.0f

	override fun enter(app: App) {
		if (mBatch == null) {
			val vTexture = app.renderer.glowTexture()
			SDL_SetTextureBlendMode(vTexture.handle, SDL_BLENDMODE_ADD)
			mBatch = SpriteBatch(app.renderer, vTexture)
		}
	}

	private fun launch(inX: Float) {
		mFireworks.add(Firework(inX, mApp.height - 30.0f, -(520.0f + mRandom.nextFloat() * 260.0f), hue(mRandom.nextFloat()), 200 + mRandom.nextInt(400)))
	}

	/** A burst at once at ([inX], [inY]). */
	private fun burstAt(inX: Float, inY: Float) {
		val vFirework = Firework(inX, inY, 0.0f, hue(mRandom.nextFloat()), 800)
		explode(vFirework)
		mFireworks.add(vFirework)
	}

	private fun explode(inFirework: Firework) {
		if (mUsesArena) arena(32768) { makeSparks(inFirework) } else makeSparks(inFirework)
		mSparksMade += inFirework.sparks
		inFirework.age = 0.001f
	}

	private fun makeSparks(inFirework: Firework) {
		for (vIndex in 0 until inFirework.sparks) {
			val vAngle = mRandom.nextFloat() * 2.0f * PI.toFloat()
			val vSpeed = 40.0f + mRandom.nextFloat() * 260.0f
			inFirework.burst.add(Spark(inFirework.x, inFirework.y, cos(vAngle) * vSpeed, sin(vAngle) * vSpeed, 6.0f + mRandom.nextFloat() * 10.0f))
		}
	}

	override fun event(event: SDL_Event) {
		when (event.kind) {
			SDL3.Event.MouseButtonDown -> burstAt(event.button.x, event.button.y)
			SDL3.Event.KeyDown -> when (event.key.code) {
				SDL3.Scancode.Space -> for (vIndex in 0 until 12) launch(80.0f + vIndex * (mApp.width - 160.0f) / 11.0f)
				SDL3.Scancode.Equals, SDL3.Scancode.KpPlus -> mRate = minOf(60.0f, mRate * 1.5f)
				SDL3.Scancode.Minus, SDL3.Scancode.KpMinus -> mRate = maxOf(1.0f, mRate / 1.5f)
				SDL3.Scancode.A -> mUsesArena = !mUsesArena
				else -> {}
			}
			else -> {}
		}
	}

	override fun update(seconds: Float) {
		val vStart = Clock.now()
		mUntilNext -= seconds
		while (mUntilNext <= 0.0f) {
			launch(100.0f + mRandom.nextFloat() * (mApp.width - 200.0f))
			mUntilNext += 1.0f / mRate
		}
		var vAlive = 0
		for (vFirework in mFireworks) {
			if (!vFirework.hasBurst) {
				vFirework.vy += 300.0f * seconds
				vFirework.y += vFirework.vy * seconds
				if (vFirework.vy > -60.0f) explode(vFirework)
				continue
			}
			vFirework.age += seconds
			val vDrag = 1.0f - 1.2f * seconds
			for (vSpark in vFirework.burst) {
				vSpark.vx *= vDrag
				vSpark.vy = vSpark.vy * vDrag + 120.0f * seconds
				vSpark.x += vSpark.vx * seconds
				vSpark.y += vSpark.vy * seconds
			}
			vAlive += vFirework.burst.size
		}
		// The bursts that are over go, and every spark of them with them.
		mFireworks.removeAll { it.isOver }
		mMostAlive = maxOf(mMostAlive, vAlive)
		mUpdateMs = mUpdateMs * 0.9f + ((Clock.now() - vStart) * 1000.0).toFloat() * 0.1f
		val vNow = Clock.now()
		if (vNow - mCountedSince >= 1.0) {
			mSparksPerSecond = ((mSparksMade - mCountedSparks) / (vNow - mCountedSince)).toInt()
			mCountedSince = vNow
			mCountedSparks = mSparksMade
		}
	}

	override fun draw(renderer: SDL3.Renderer) {
		val vBatch = mBatch ?: return
		vBatch.begin()
		for (vFirework in mFireworks) {
			val vColor = vFirework.color
			val vFade = if (vFirework.hasBurst) (1.0f - vFirework.age / 2.6f).coerceIn(0.0f, 1.0f) else 1.0f
			vBatch.color(vColor.r.toInt() / 255.0f, vColor.g.toInt() / 255.0f, vColor.b.toInt() / 255.0f, vFade)
			if (!vFirework.hasBurst) {
				vBatch.add(vFirework.x - 8.0f, vFirework.y - 8.0f, 16.0f, 16.0f)
				continue
			}
			for (vSpark in vFirework.burst) {
				val vSize = vSpark.size * (0.4f + vFade * 0.6f)
				vBatch.add(vSpark.x - vSize / 2.0f, vSpark.y - vSize / 2.0f, vSize, vSize)
			}
		}
		vBatch.flush()
	}

	override fun status(): String =
		"${mFireworks.sumOf { it.burst.size }} sparks alive   $mSparksPerSecond made a second   update ${(mUpdateMs * 100).toInt() / 100.0f} ms   arena ${if (mUsesArena) "on" else "off"}"

	override fun autoStep(frame: Int): Boolean {
		if (frame == 20) mRate = 30.0f
		if (frame == 120) mUsesArena = !mUsesArena
		if (frame % 40 == 39) burstAt(200.0f + (frame * 37 % 800), 200.0f + (frame * 13 % 200))
		return frame < 200
	}

	override fun report(): String = "$mSparksMade sparks made, up to $mMostAlive alive at once"
}
