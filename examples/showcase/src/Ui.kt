@file:OptIn(ExperimentalForeignApi::class)

package showcase.app

import kotlinx.cinterop.ExperimentalForeignApi
import ktc2.sdl3.*
import showcase.core.Group
import showcase.core.Outcome
import showcase.core.kFailedMark
import showcase.core.kFoundMark
import showcase.core.kPassedMark
import showcase.core.summary

// ==================
// MARK: Layout
// ==================

/*
Everything is drawn at twice its size: the text of SDL's built-in font is 8 pixels high. The
positions below are in those doubled units.
*/
internal const val kScale = 2.0f
internal const val kWidth = 460.0f
internal const val kHeight = 300.0f
private const val kButtonLeft = 8.0f
private const val kButtonWidth = 132.0f
private const val kButtonHeight = 20.0f
private const val kButtonGap = 5.0f
private const val kLineHeight = 10.0f

/** The panel the log is shown in, the room for its lines under its title, and the bar to scroll it by. */
private val kPanel = SDL3.FRect(150.0f, 6.0f, kWidth - 156.0f, kHeight - 12.0f)
private val kLines = SDL3.FRect(kPanel.x + 6.0f, kPanel.y + 20.0f, kPanel.w - 22.0f, kPanel.h - 26.0f)
private val kTrack = SDL3.FRect(kPanel.x + kPanel.w - 10.0f, kLines.y, 6.0f, kLines.h)

/** How many lines the panel shows at once, and how many characters of the built-in font fit in one. */
private val kRows = (kLines.h / kLineHeight).toInt()
private val kLineWidth = (kLines.w / 8.0f).toInt()

private val kBackground = SDL3.Color(0x1B1F27FFu)
private val kPanelColor = SDL3.Color(0x252B36FFu)
private val kIdle = SDL3.Color(0x3A4556FFu)
private val kHover = SDL3.Color(0x4C5B73FFu)
private val kPassed = SDL3.Color(0x2E7D4FFFu)
private val kFailed = SDL3.Color(0xA83A3AFFu)
private val kText = SDL3.Color(0xE8ECF1FFu)
private val kDim = SDL3.Color(0x8A94A3FFu)
private val kGood = SDL3.Color(0x7FD39AFFu)
private val kBad = SDL3.Color(0xFF8A8AFFu)
private val kTitle = SDL3.Color(0xFFD479FFu)

/** How a button looks: not run yet, run without a failure, run with one. */
enum class State { IDLE, PASSED, FAILED }

/** What the buttons that are not a group stand for. */
internal const val kRunAll = -1
internal const val kClearLog = -2

/** Something to click. [action] is the place of its group among the groups, or [kRunAll] or [kClearLog]. */
class Button(val label: String, val action: Int, val bounds: SDL3.FRect) {
	var state = State.IDLE

	fun contains(inX: Float, inY: Float): Boolean = bounds.contains(SDL3.FPoint(inX, inY))
}

// ==================
// MARK: The screen
// ==================

/** The buttons, the log of what the clicks did, and how to draw both. */
class Screen(private val mGroups: List<Group>) {
	val buttons = ArrayList<Button>()

	/** Every line logged since the start or the last clearing, already cut to the width of the panel. */
	private val mLog = ArrayList<String>()

	/** The first line of the log that is shown. */
	private var mTop = 0
	private val mOutcomes = HashMap<Int, Outcome>()
	private var mHovered = -1

	/** Set while the bar is held: the mouse then moves the log. */
	private var mIsDragging = false

	/** How many clicks on a button were handled: `--auto` reports it. */
	var clicks = 0
		private set

	init {
		var vTop = 26.0f
		mGroups.forEachIndexed { vIndex, vGroup ->
			buttons.add(Button(vGroup.title, vIndex, SDL3.FRect(kButtonLeft, vTop, kButtonWidth, kButtonHeight)))
			vTop += kButtonHeight + kButtonGap
		}
		buttons.add(Button("Run all", kRunAll, SDL3.FRect(kButtonLeft, vTop + kButtonGap, kButtonWidth, kButtonHeight)))
		buttons.add(Button("Clear log", kClearLog, SDL3.FRect(kButtonLeft, vTop + kButtonGap + kButtonHeight + kButtonGap, kButtonWidth, kButtonHeight)))
		addLine("Click a group to run its tests.")
		addLine("Wheel, arrows, Page Up and Down, Home and End move through the log.")
	}

	val failures: Int get() = mOutcomes.values.sumOf { it.failed }

	val groupsRun: Int get() = mOutcomes.size

	val logSize: Int get() = mLog.size

	val firstShown: Int get() = mTop

	// ============
	//  Scrolling

	private val mLastTop: Int get() = maxOf(0, mLog.size - kRows)

	/** Moves the log by [inLines], down when positive. */
	fun scroll(inLines: Int) {
		mTop = (mTop + inLines).coerceIn(0, mLastTop)
	}

	fun page(inPages: Int) {
		scroll(inPages * (kRows - 1))
	}

	fun scrollToStart() {
		mTop = 0
	}

	fun scrollToEnd() {
		mTop = mLastTop
	}

	/** Puts the log where the bar is held, at height [inY]. */
	private fun drag(inY: Float) {
		val vPlace = ((inY - kTrack.y) / kTrack.h).coerceIn(0.0f, 1.0f)
		mTop = (vPlace * mLog.size - kRows / 2).toInt().coerceIn(0, mLastTop)
	}

	// ============
	//  Mouse

	fun move(inX: Float, inY: Float) {
		mHovered = buttons.indexOfFirst { it.contains(inX, inY) }
		if (mIsDragging) drag(inY)
	}

	fun release() {
		mIsDragging = false
	}

	/** The button was pressed at ([inX], [inY]): on a button it does what the button stands for, on the bar it takes hold of the log. Returns true when it hit a button. */
	fun press(inX: Float, inY: Float): Boolean {
		if (kTrack.grow(4.0f).contains(SDL3.FPoint(inX, inY))) {
			mIsDragging = true
			drag(inY)
			return false
		}
		val vButton = buttons.firstOrNull { it.contains(inX, inY) } ?: return false
		clicks++
		when (vButton.action) {
			kClearLog -> {
				mLog.clear()
				mTop = 0
				addLine("Log cleared.")
			}
			kRunAll -> {
				val vStart = startSection("Run all")
				val vOutcomes = mGroups.indices.map { run(it) }
				vOutcomes.forEach { vOutcome -> vOutcome.log().forEach { addLine(it) } }
				addLine("")
				addLine(summary(vOutcomes))
				vButton.state = if (vOutcomes.all { it.isSuccess }) State.PASSED else State.FAILED
				mTop = vStart.coerceIn(0, mLastTop)
			}
			else -> {
				val vStart = startSection(vButton.label)
				run(vButton.action).log().forEach { addLine(it) }
				mTop = vStart.coerceIn(0, mLastTop)
			}
		}
		return true
	}

	/** Begins what a click adds to the log, after what is there. Returns the line it begins at. */
	private fun startSection(inTitle: String): Int {
		if (mLog.isNotEmpty()) addLine("")
		val vStart = mLog.size
		addLine("> $inTitle")
		return vStart
	}

	private fun run(inGroup: Int): Outcome {
		val vOutcome = mGroups[inGroup].run()
		mOutcomes[inGroup] = vOutcome
		buttons[inGroup].state = if (vOutcome.isSuccess) State.PASSED else State.FAILED
		return vOutcome
	}

	/** Adds [inLine] to the log; a long one goes on below, under its text, cut between words where it can be. */
	private fun addLine(inLine: String) {
		// What follows the mark of a line stays to the right of it.
		val vIndent = if (inLine.startsWith(kPassedMark) || inLine.startsWith(kFailedMark) || inLine.startsWith(kFoundMark)) kFoundMark else "  "
		var vRest = inLine
		var vIsFirst = true
		if (vRest.isEmpty()) mLog.add("")
		while (vRest.isNotEmpty()) {
			val vRoom = if (vIsFirst) kLineWidth else kLineWidth - vIndent.length
			var vCut = if (vRest.length <= vRoom) vRest.length else vRest.lastIndexOf(' ', vRoom)
			if (vCut <= (if (vIsFirst) vIndent.length else 0)) vCut = minOf(vRoom, vRest.length)
			mLog.add((if (vIsFirst) "" else vIndent) + vRest.substring(0, vCut))
			vRest = vRest.substring(vCut).trimStart()
			vIsFirst = false
		}
	}

	// ============
	//  Drawing

	fun draw(inRenderer: SDL3.Renderer) {
		inRenderer.setDrawColor(kBackground)
		inRenderer.clear()
		inRenderer.setDrawColor(kText)
		inRenderer.drawDebugText(kButtonLeft, 8.0f, "ktc2 showcase")

		buttons.forEachIndexed { vIndex, vButton ->
			val vColor = when {
				vButton.state == State.PASSED -> kPassed
				vButton.state == State.FAILED -> kFailed
				vIndex == mHovered -> kHover
				else -> kIdle
			}
			inRenderer.setDrawColor(vColor)
			inRenderer.fillRoundedRect(vButton.bounds, 4.0f)
			if (vIndex == mHovered) {
				inRenderer.setDrawColor(kText)
				inRenderer.drawRoundedRect(vButton.bounds, 4.0f)
			}
			inRenderer.setDrawColor(kText)
			inRenderer.drawDebugText(vButton.bounds.x + 6.0f, vButton.bounds.y + 6.0f, vButton.label)
		}

		inRenderer.setDrawColor(kPanelColor)
		inRenderer.fillRoundedRect(kPanel, 4.0f)
		val vLast = minOf(mLog.size, mTop + kRows)
		inRenderer.setDrawColor(kDim)
		inRenderer.drawDebugText(kPanel.x + 6.0f, kPanel.y + 6.0f, "Log: lines ${if (mLog.isEmpty()) 0 else mTop + 1}-$vLast of ${mLog.size}")
		for (vIndex in mTop until vLast) {
			val vLine = mLog[vIndex]
			val vColor = when {
				vLine.startsWith(kFailedMark) -> kBad
				vLine.startsWith(kPassedMark) -> kGood
				vLine.startsWith(kFoundMark) -> kDim
				vLine.startsWith(">") -> kTitle
				else -> kText
			}
			inRenderer.setDrawColor(vColor)
			inRenderer.drawDebugText(kLines.x, kLines.y + (vIndex - mTop) * kLineHeight, vLine)
		}

		// The bar: as long, of the track, as the part of the log that is shown.
		if (mLog.size > kRows) {
			inRenderer.setDrawColor(kBackground)
			inRenderer.fillRoundedRect(kTrack, 3.0f)
			val vLength = maxOf(8.0f, kTrack.h * kRows / mLog.size)
			val vOffset = (kTrack.h - vLength) * mTop / mLastTop
			inRenderer.setDrawColor(if (mIsDragging) kText else kHover)
			inRenderer.fillRoundedRect(SDL3.FRect(kTrack.x, kTrack.y + vOffset, kTrack.w, vLength), 3.0f)
		}
		inRenderer.present()
	}
}
