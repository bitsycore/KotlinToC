@file:OptIn(ExperimentalForeignApi::class)

package showcase.scenes

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ktc2.sdl3.*
import sdl3.SDL_Event
import showcase.app.App
import showcase.app.gApp
import showcase.app.Buttons
import showcase.app.Scene
import showcase.app.Theme
import showcase.app.panel
import showcase.app.kSmallText
import showcase.app.text
import showcase.app.wrap
import kotlin.random.Random
import kotlin.time.Clock

/** A player of the score table: what the page edits, saves and loads. */
@Serializable
data class Player(val name: String, val score: Int, val tags: List<String>)

/** What the page keeps: saved as JSON (kotlinx.serialization) into a file (kotlinx-io). */
@Serializable
data class Board(val title: String, val players: List<Player>, val savedAt: String? = null, val edits: Int = 0)

/**
 * The libraries of Kotlin that ktc2 compiles from their own sources, together: a board of scores
 * kept as immutable values, every change an entry of an undo history (kotlinx.collections.immutable),
 * saved as JSON (kotlinx.serialization) into a file (kotlinx-io), dated (kotlinx-datetime), and
 * searched with a regular expression and a sequence.
 */
class DataScene : Scene {
	override val title = "Data"
	override val summary = "JSON, files, dates, immutable undo, Regex: the kotlinx libraries"
	override val help = "Buttons, or N new player  S save  L load  U undo  F filter"

	/** The app, from its global: a field of its own would close a cycle (the app holds its pages). */
	private val mApp: App get() = gApp
	private val mButtons = Buttons()
	private val mRandom = Random(9)
	private val mJson = Json { prettyPrint = true }
	private val mFile = Path("showcase-board.json")

	/** Every state the board was in, the current one last: undo drops the last. */
	private var mHistory: PersistentList<Board> = persistentListOf(Board("ktc2 scores", listOf(Player("Ada", 120, listOf("first")), Player("Linus", 95, listOf()))))
	private var mFilter: Regex? = null
	private var mMessage = "Ready."
	private var mLastSaved = 0

	private val mBoard: Board get() = mHistory.last()

	private val kNames = listOf("Grace", "Alan", "Barbara", "Edsger", "Margaret", "Dennis", "Ken", "Niklaus", "Frances", "John", "Radia", "Bjarne")
	private val kTags = listOf("fast", "steady", "lucky", "new", "veteran", "night owl")

	override fun enter(app: App) {
		if (mButtons.all.isNotEmpty()) return
		var vY = 60.0f
		mButtons.add("New player", 30.0f, vY, 240.0f) { newPlayer() }; vY += 46.0f
		mButtons.add("Shuffle scores", 30.0f, vY, 240.0f) { shuffle() }; vY += 46.0f
		mButtons.add("Undo", 30.0f, vY, 240.0f) { undo() }; vY += 46.0f
		mButtons.add("Save", 30.0f, vY, 240.0f) { save() }; vY += 46.0f
		mButtons.add("Load", 30.0f, vY, 240.0f) { load() }; vY += 46.0f
		mButtons.add("Filter /^[A-G]/", 30.0f, vY, 240.0f) { toggleFilter() }
	}

	/** A new state of the board: the old one stays in the history, nothing is copied that did not change. */
	private fun change(inBoard: Board, inMessage: String) {
		mHistory = mHistory.add(inBoard.copy(edits = mBoard.edits + 1))
		mMessage = inMessage
	}

	private fun newPlayer() {
		val vName = kNames[mRandom.nextInt(kNames.size)] + " " + (mBoard.players.size + 1)
		val vPlayer = Player(vName, mRandom.nextInt(200), kTags.shuffled(mRandom).take(mRandom.nextInt(3)))
		change(mBoard.copy(players = mBoard.players + vPlayer), "Added $vName.")
	}

	private fun shuffle() {
		change(mBoard.copy(players = mBoard.players.map { it.copy(score = (it.score + mRandom.nextInt(-40, 60)).coerceAtLeast(0)) }), "Scores shuffled.")
	}

	private fun undo() {
		if (mHistory.size == 1) {
			mMessage = "Nothing to undo."
			return
		}
		mHistory = mHistory.removeAt(mHistory.size - 1)
		mMessage = "Undone: ${mHistory.size} state${if (mHistory.size > 1) "s" else ""} left."
	}

	private fun now(): LocalDateTime {
		val vZone = try {
			TimeZone.currentSystemDefault()
		} catch (vError: Exception) {
			TimeZone.UTC
		}
		return Clock.System.now().toLocalDateTime(vZone)
	}

	private fun save() {
		val vBoard = mBoard.copy(savedAt = now().toString())
		val vText = mJson.encodeToString(Board.serializer(), vBoard)
		SystemFileSystem.sink(mFile).buffered().use { it.writeString(vText) }
		mLastSaved = vText.length
		mMessage = "Saved ${vText.length} characters to $mFile."
	}

	private fun load() {
		if (!SystemFileSystem.exists(mFile)) {
			mMessage = "No $mFile yet: save first."
			return
		}
		val vText = SystemFileSystem.source(mFile).buffered().use { it.readString() }
		val vBoard = mJson.decodeFromString(Board.serializer(), vText)
		change(vBoard, "Loaded ${vBoard.players.size} players, saved at ${vBoard.savedAt}.")
	}

	private fun toggleFilter() {
		mFilter = if (mFilter == null) Regex("^[A-G]") else null
		mMessage = if (mFilter == null) "Filter off." else "Only the names that start with A to G."
	}

	override fun event(event: SDL_Event) {
		when (event.kind) {
			SDL3.Event.MouseMotion -> mButtons.move(event.motion.x, event.motion.y)
			SDL3.Event.MouseButtonDown -> mButtons.click(event.button.x, event.button.y)
			SDL3.Event.KeyDown -> when (event.key.code) {
				SDL3.Scancode.N -> newPlayer()
				SDL3.Scancode.S -> save()
				SDL3.Scancode.L -> load()
				SDL3.Scancode.U -> undo()
				SDL3.Scancode.F -> toggleFilter()
				else -> {}
			}
			else -> {}
		}
	}

	override fun update(seconds: Float) {}

	override fun draw(renderer: SDL3.Renderer) {
		mButtons.draw(renderer)
		// The left column: 240 pixels, the smaller text, the message on as many lines as it takes.
		var vLineY = 350.0f
		for (vLine in wrap(mMessage, 240.0f, kSmallText)) {
			renderer.text(30.0f, vLineY, vLine, Theme.accent, kSmallText)
			vLineY += 16.0f
		}
		val vNow = now()
		renderer.text(30.0f, vLineY + 20.0f, "Today ${vNow.date}", Theme.dim, kSmallText)
		renderer.text(30.0f, vLineY + 38.0f, "Time  ${vNow.time.toString().take(8)}", Theme.dim, kSmallText)
		renderer.text(30.0f, vLineY + 62.0f, "Undo history: ${mHistory.size}", Theme.dim, kSmallText)
		renderer.text(30.0f, vLineY + 80.0f, "Edits: ${mBoard.edits}", Theme.dim, kSmallText)

		// The table, best first, through the filter: a sequence, so nothing is made for what is left out.
		val vTable = SDL3.FRect(300.0f, 50.0f, 440.0f, mApp.height - 110.0f)
		renderer.panel(vTable)
		renderer.text(vTable.x + 16.0f, vTable.y + 14.0f, mBoard.title, Theme.accent)
		val vShown = mBoard.players.asSequence()
			.filter { vPlayer -> mFilter?.containsMatchIn(vPlayer.name) ?: true }
			.sortedByDescending { it.score }
			.take(24)
		var vY = vTable.y + 50.0f
		for ((vRank, vPlayer) in vShown.withIndex()) {
			renderer.text(vTable.x + 16.0f, vY, "${vRank + 1}.".padEnd(4) + vPlayer.name.take(16).padEnd(17) + vPlayer.score.toString().padStart(4), if (vRank == 0) Theme.good else Theme.text)
			vY += 22.0f
		}

		// The JSON of the board as it would be saved.
		val vJsonPanel = SDL3.FRect(760.0f, 50.0f, mApp.width - 800.0f, mApp.height - 110.0f)
		renderer.panel(vJsonPanel)
		renderer.text(vJsonPanel.x + 16.0f, vJsonPanel.y + 14.0f, "JSON", Theme.accent)
		val vLines = mJson.encodeToString(Board.serializer(), mBoard).lines()
		val vRoom = ((vJsonPanel.h - 60.0f) / 18.0f).toInt()
		for ((vIndex, vLine) in vLines.take(vRoom).withIndex()) {
			renderer.setScale(1.5f, 1.5f)
			renderer.setDrawColor(if (vLine.trimStart().startsWith("\"")) Theme.text else Theme.dim)
			renderer.drawDebugText((vJsonPanel.x + 16.0f) / 1.5f, (vJsonPanel.y + 48.0f + vIndex * 18.0f) / 1.5f, vLine.take(38))
			renderer.setScale(1.0f, 1.0f)
		}
	}

	override fun status(): String = "${mBoard.players.size} players   ${mHistory.size} states   file $mFile"

	override fun autoStep(frame: Int): Boolean {
		when (frame) {
			10, 20, 30, 40 -> newPlayer()
			50 -> shuffle()
			60 -> save()
			70 -> undo()
			80 -> load()
			90 -> toggleFilter()
		}
		return frame < 120
	}

	override fun report(): String = "${mBoard.players.size} players, $mLastSaved characters of JSON saved and loaded, ${mHistory.size} states of undo. $mMessage"
}
