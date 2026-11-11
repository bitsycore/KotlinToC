@file:OptIn(ExperimentalForeignApi::class)

package showcase.app

import kotlinx.cinterop.ExperimentalForeignApi
import ktc.sdl3.*
import showcase.core.Group
import showcase.core.Outcome
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
private const val kButtonHeight = 22.0f
private const val kButtonGap = 6.0f
private const val kPanelLeft = 150.0f
private const val kLineHeight = 10.0f

private val kBackground = SDL3.Color(0x1B1F27FFu)
private val kPanel = SDL3.Color(0x252B36FFu)
private val kIdle = SDL3.Color(0x3A4556FFu)
private val kHover = SDL3.Color(0x4C5B73FFu)
private val kPassed = SDL3.Color(0x2E7D4FFFu)
private val kFailed = SDL3.Color(0xA83A3AFFu)
private val kText = SDL3.Color(0xE8ECF1FFu)
private val kDim = SDL3.Color(0x8A94A3FFu)

/** How a button looks: not run yet, run without a failure, run with one. */
enum class State { IDLE, PASSED, FAILED }

/** Something to click. [group] is the place of its group among the groups, or -1 for the button that runs them all. */
class Button(val label: String, val group: Int, val bounds: SDL3.FRect) {
	var state = State.IDLE

	fun contains(inX: Float, inY: Float): Boolean = bounds.contains(SDL3.FPoint(inX, inY))
}

// ==================
// MARK: The screen
// ==================

/** The buttons, what the last click gave, and how to draw both. */
class Screen(private val mGroups: List<Group>) {
	val buttons = ArrayList<Button>()

	/** The lines shown on the right, and whether each reports a failure. */
	private val mLines = ArrayList<String>()
	private val mOutcomes = HashMap<Int, Outcome>()
	private var mHovered = -1

	/** How many clicks were handled: the window shows it, and `--auto` reports it. */
	var clicks = 0
		private set

	init {
		var vTop = 26.0f
		mGroups.forEachIndexed { vIndex, vGroup ->
			buttons.add(Button(vGroup.title, vIndex, SDL3.FRect(kButtonLeft, vTop, kButtonWidth, kButtonHeight)))
			vTop += kButtonHeight + kButtonGap
		}
		buttons.add(Button("Run all", -1, SDL3.FRect(kButtonLeft, vTop + kButtonGap, kButtonWidth, kButtonHeight)))
		mLines.add("Click a group to run its tests.")
	}

	val failures: Int get() = mOutcomes.values.sumOf { it.failed }

	val groupsRun: Int get() = mOutcomes.size

	fun hover(inX: Float, inY: Float) {
		mHovered = buttons.indexOfFirst { it.contains(inX, inY) }
	}

	/** A click at ([inX], [inY]): runs what the button there stands for. Returns true when it hit a button. */
	fun click(inX: Float, inY: Float): Boolean {
		val vButton = buttons.firstOrNull { it.contains(inX, inY) } ?: return false
		clicks++
		mLines.clear()
		if (vButton.group >= 0) {
			show(run(vButton.group))
		} else {
			val vOutcomes = mGroups.indices.map { run(it) }
			vOutcomes.forEach { show(it) }
			mLines.add("")
			addLine(summary(vOutcomes))
			vButton.state = if (vOutcomes.all { it.isSuccess }) State.PASSED else State.FAILED
		}
		return true
	}

	private fun run(inGroup: Int): Outcome {
		val vOutcome = mGroups[inGroup].run()
		mOutcomes[inGroup] = vOutcome
		buttons[inGroup].state = if (vOutcome.isSuccess) State.PASSED else State.FAILED
		return vOutcome
	}

	private fun show(inOutcome: Outcome) {
		for (vLine in inOutcome.lines()) addLine(vLine)
	}

	/** Adds [inLine] to what is shown; a long one goes on below, indented, cut between words where it can be. */
	private fun addLine(inLine: String) {
		var vRest = inLine
		var vIndent = ""
		while (vRest.isNotEmpty()) {
			val vRoom = kLineWidth - vIndent.length
			var vCut = if (vRest.length <= vRoom) vRest.length else vRest.lastIndexOf(' ', vRoom)
			if (vCut <= 0) vCut = minOf(vRoom, vRest.length)
			mLines.add(vIndent + vRest.substring(0, vCut))
			vRest = vRest.substring(vCut).trimStart()
			vIndent = "    "
		}
	}

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
			inRenderer.drawDebugText(vButton.bounds.x + 6.0f, vButton.bounds.y + 7.0f, vButton.label)
		}

		val vPanel = SDL3.FRect(kPanelLeft, 6.0f, kWidth - kPanelLeft - 6.0f, kHeight - 12.0f)
		inRenderer.setDrawColor(kPanel)
		inRenderer.fillRoundedRect(vPanel, 4.0f)
		val vRoom = ((vPanel.h - 12.0f) / kLineHeight).toInt()
		// The end of a long report is what matters: its summary.
		val vShown = if (mLines.size > vRoom) mLines.subList(mLines.size - vRoom, mLines.size) else mLines
		vShown.forEachIndexed { vIndex, vLine ->
			inRenderer.setDrawColor(if ("FAILED" in vLine) kFailed else if (vLine.startsWith(" ")) kDim else kText)
			inRenderer.drawDebugText(vPanel.x + 6.0f, vPanel.y + 6.0f + vIndex * kLineHeight, vLine)
		}
		inRenderer.present()
	}

	private companion object {
		/** How many characters of the built-in font fit in a line of the panel. */
		const val kLineWidth = 36
	}
}
