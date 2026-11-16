@file:OptIn(ExperimentalForeignApi::class)

package showcase.scenes

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.sdl3.*
import sdl3.SDL_Event
import showcase.app.App
import showcase.app.gApp
import showcase.app.Clock
import showcase.app.Scene
import showcase.gfx.streamingTexture
import showcase.gfx.upload
import kotlin.random.Random

/**
 * Conway's game of life on a grid of 640 x 332 cells (a byte each, two grids that take turns), as
 * many generations a frame as asked, drawn into a texture where a cell that just died glows a
 * little longer. The mouse draws cells.
 */
class LifeScene : Scene {
	override val title = "Life"
	override val summary = "Conway's game of life, many generations a frame, in a texture"
	override val help = "Drag to draw   Space pause   +/- speed   R random   C clear   G glider gun"

	/** The app, from its global: a field of its own would close a cycle (the app holds its pages). */
	private val mApp: App get() = gApp
	private val kColumns = 640
	private val kRows = 332
	private var mCells = ByteArray(kColumns * kRows)
	private var mNext = ByteArray(kColumns * kRows)

	/** How long ago each cell was alive: what makes the trail. */
	private val mAge = ByteArray(kColumns * kRows)
	private val mPixels = IntArray(kColumns * kRows)
	private var mTexture: SDL3.Texture? = null
	private var mGenerations = 0L
	private var mPerFrame = 1
	private var mPaused = false
	private var mDrawing = false
	private var mStepMs = 0.0f
	private val mRandom = Random(5)

	override fun enter(app: App) {
		if (mTexture == null) {
			mTexture = app.renderer.streamingTexture(kColumns, kRows)
			randomize()
		}
	}

	private fun randomize() {
		for (vIndex in mCells.indices) mCells[vIndex] = if (mRandom.nextInt(100) < 22) 1 else 0
	}

	/** Gosper's gun, which sends a glider off every 30 generations, at ([inX], [inY]). */
	private fun gun(inX: Int, inY: Int) {
		val vShape = listOf(
			"........................#...........",
			"......................#.#...........",
			"............##......##............##",
			"...........#...#....##............##",
			"##........#.....#...##..............",
			"##........#...#.##....#.#...........",
			"..........#.....#.......#...........",
			"...........#...#....................",
			"............##......................",
		)
		for ((vRow, vLine) in vShape.withIndex()) for ((vColumn, vChar) in vLine.withIndex()) {
			if (vChar == '#') mCells[(inY + vRow) * kColumns + inX + vColumn] = 1
		}
	}

	private fun paint(inX: Float, inY: Float) {
		val vColumn = (inX / mApp.width * kColumns).toInt()
		val vRow = ((inY - 28.0f) / (mApp.height - 56.0f) * kRows).toInt()
		for (vDy in -2..2) for (vDx in -2..2) {
			val vX = vColumn + vDx
			val vY = vRow + vDy
			if (vX in 0 until kColumns && vY in 0 until kRows) mCells[vY * kColumns + vX] = 1
		}
	}

	override fun event(event: SDL_Event) {
		when (event.kind) {
			SDL3.Event.KeyDown -> when (event.key.code) {
				SDL3.Scancode.Space -> mPaused = !mPaused
				SDL3.Scancode.Equals, SDL3.Scancode.KpPlus -> mPerFrame = minOf(64, mPerFrame * 2)
				SDL3.Scancode.Minus, SDL3.Scancode.KpMinus -> mPerFrame = maxOf(1, mPerFrame / 2)
				SDL3.Scancode.R -> randomize()
				SDL3.Scancode.C -> mCells.fill(0)
				SDL3.Scancode.G -> { mCells.fill(0); gun(20, 20); gun(200, 120); gun(400, 220) }
				else -> {}
			}
			SDL3.Event.MouseButtonDown -> { mDrawing = true; paint(event.button.x, event.button.y) }
			SDL3.Event.MouseMotion -> if (mDrawing) paint(event.motion.x, event.motion.y)
			SDL3.Event.MouseButtonUp -> mDrawing = false
			else -> {}
		}
	}

	/** One generation: each cell from its eight neighbours, the edges wrapping around. */
	private fun generation() {
		val vCells = mCells
		val vNext = mNext
		for (vRow in 0 until kRows) {
			val vAbove = ((vRow + kRows - 1) % kRows) * kColumns
			val vHere = vRow * kColumns
			val vBelow = ((vRow + 1) % kRows) * kColumns
			for (vColumn in 0 until kColumns) {
				val vLeft = if (vColumn == 0) kColumns - 1 else vColumn - 1
				val vRight = if (vColumn == kColumns - 1) 0 else vColumn + 1
				val vAround = vCells[vAbove + vLeft] + vCells[vAbove + vColumn] + vCells[vAbove + vRight] +
					vCells[vHere + vLeft] + vCells[vHere + vRight] +
					vCells[vBelow + vLeft] + vCells[vBelow + vColumn] + vCells[vBelow + vRight]
				val vAlive = vCells[vHere + vColumn].toInt() == 1
				vNext[vHere + vColumn] = if (vAround == 3 || (vAlive && vAround == 2)) 1 else 0
			}
		}
		mNext = vCells
		mCells = vNext
		mGenerations++
	}

	override fun update(seconds: Float) {
		val vStart = Clock.now()
		if (!mPaused) repeat(mPerFrame) { generation() }
		for (vIndex in mCells.indices) {
			val vAge = if (mCells[vIndex].toInt() == 1) 0 else minOf(60, mAge[vIndex] + 1)
			mAge[vIndex] = vAge.toByte()
			mPixels[vIndex] = if (vAge == 0) -1 else {
				val vGlow = (60 - vAge) * 3
				(255 shl 24) or (vGlow shl 16) or ((vGlow / 3) shl 8) or (vGlow / 4)
			}
		}
		mTexture?.upload(mPixels, kColumns)
		mStepMs = mStepMs * 0.9f + ((Clock.now() - vStart) * 1000.0).toFloat() * 0.1f
	}

	override fun draw(renderer: SDL3.Renderer) {
		val vTexture = mTexture ?: return
		renderer.renderTexture(vTexture, SDL3.FRect(0.0f, 28.0f, mApp.width.toFloat(), mApp.height - 56.0f))
	}

	override fun status(): String =
		"generation $mGenerations   ${mCells.count { it.toInt() == 1 }} alive   $mPerFrame a frame   ${(mStepMs * 10).toInt() / 10.0f} ms${if (mPaused) "   paused" else ""}"

	override fun autoStep(frame: Int): Boolean {
		if (frame == 30) mPerFrame = 8
		if (frame == 100) { mCells.fill(0); gun(20, 20); gun(200, 120); gun(400, 220) }
		return frame < 200
	}

	override fun report(): String = "$mGenerations generations, ${(mStepMs * 10).toInt() / 10.0f} ms a frame"
}
