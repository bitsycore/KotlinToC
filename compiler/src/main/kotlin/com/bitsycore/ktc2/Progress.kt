package com.bitsycore.ktc2

/** The argument the command adds when it runs in a terminal: [Progress] then rewrites a step's line in place. */
const val kTerminalArgument = "--ktc2-terminal"

/**
 * What a command is doing, one line for each step on the standard error: what it is, what it took, and how long.
 * In a terminal the line of a step is written when it starts and rewritten as it goes and when it ends; elsewhere
 * (a log, a pipe) only when it ends. `--quiet` (or `KTC2_QUIET=1`) leaves them out; errors and warnings stay.
 */
object Progress {
	/** True when the command runs in a terminal ([kTerminalArgument]). */
	@Volatile
	var isTerminal = false

	/** True for a command (`runCommand`), but for `--quiet`; false otherwise (the suite calls the compiler itself). */
	@Volatile
	var isShown = false

	private var mStep: String? = null
	private var mStart = 0L

	/** The standard error as it was before a step under way in a terminal took it ([begin]). */
	private var mErr: java.io.PrintStream? = null

	/** True when something else was printed on the line of the step under way. */
	@Volatile
	private var mBroken = false

	/** The width of a step's name, so that what follows is in one column. */
	private const val kNameWidth = 14

	/** Starts the step [inName], with [inDetail] while it runs. */
	@Synchronized
	fun begin(inName: String, inDetail: String = "") {
		mStep = inName
		mStart = System.nanoTime()
		if (isShown && isTerminal) {
			write(line(inName, inDetail, null), false)
			// What else is printed meanwhile (a warning, an error of the compiler) starts on a line of its own.
			val vErr = System.err
			mErr = vErr
			mBroken = false
			System.setErr(java.io.PrintStream(object : java.io.OutputStream() {
				override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
				override fun write(b: ByteArray, off: Int, len: Int) {
					if (!mBroken) {
						mBroken = true
						vErr.write('\n'.code)
					}
					vErr.write(b, off, len)
				}
				override fun flush() = vErr.flush()
			}, true))
		}
	}

	/** When the line of the step under way was last written: it is written again at most ten times a second. */
	private var mShown = 0L

	/** Shows [inDetail] on the line of the step under way (a count: `[37/96]`), in a terminal only. */
	@Synchronized
	fun update(inDetail: String) {
		val vStep = mStep ?: return
		val vNow = System.nanoTime()
		if (!isShown || !isTerminal || vNow - mShown < 100_000_000) return
		mShown = vNow
		write(line(vStep, inDetail, null), false)
	}

	/** Ends the step under way, saying [inDetail]: its line with the time it took. */
	@Synchronized
	fun end(inDetail: String = "") {
		val vStep = mStep ?: return
		mStep = null
		mErr?.let { System.setErr(it) }
		mErr = null
		if (isShown) write(line(vStep, inDetail, (System.nanoTime() - mStart) / 1_000_000), true)
	}

	/** Ends the step under way, if there is one, as one that failed. */
	@Synchronized
	fun fail() {
		if (mStep != null) end("failed")
	}

	/** A step that took no time worth showing: its line at once. */
	@Synchronized
	fun note(inName: String, inDetail: String) {
		if (isShown) write(line(inName, inDetail, null), true)
	}

	/**
	 * Something else is printed (a warning, what fetching says): a line under way is ended first, so that it is not
	 * written over, and written again after.
	 */
	@Synchronized
	fun interrupt(inPrint: () -> Unit) {
		val vStep = mStep
		val vErr = mErr
		if (vStep != null && isShown && isTerminal && vErr != null) {
			vErr.print("\r\u001b[K")
			vErr.flush()
			System.setErr(vErr)
			inPrint()
			mErr = null
			val vStart = mStart
			begin(vStep)
			mStart = vStart
			return
		}
		inPrint()
	}

	private fun line(inName: String, inDetail: String, inMillis: Long?): String {
		val vTime = inMillis?.let { if (it < 1000) "$it ms" else "%.1f s".format(java.util.Locale.ROOT, it / 1000.0) }
		val vText = "ktc2: " + inName.padEnd(kNameWidth) + inDetail
		return if (vTime == null) vText else vText.padEnd(72) + " " + vTime
	}

	private fun write(inText: String, inDone: Boolean) {
		val vErr = mErr ?: System.err
		// After a line that something else broke, the step's own starts afresh.
		if (isTerminal) vErr.print("\r\u001b[K" + inText + if (inDone) "\n" else "") else if (inDone) vErr.println(inText)
		vErr.flush()
	}
}
