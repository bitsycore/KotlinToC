package ktc2.concurrent

// ==================
// MARK: Thread
// ==================

/*
The JVM side of `ktc2.concurrent`, for running a ktc2 program on the JVM: the same API over
the threads and monitors of the JDK. The C side is ktc2's platform layer.
*/

/**
 * A thread of the operating system that runs [run] once started. A property written by one
 * thread while another uses it needs [synchronized].
 */
open class Thread() {

	private var mBlock: (() -> Unit)? = null
	private var mStarted = false
	private val mThread = java.lang.Thread { run() }

	constructor(block: () -> Unit) : this() {
		mBlock = block
	}

	/** What the thread does: the block it was built with, unless overridden. */
	open fun run() {
		mBlock?.invoke()
	}

	fun start() {
		if (mStarted) throw IllegalStateException("Thread already started")
		mStarted = true
		mThread.start()
	}

	/** Waits for the thread to end; returns at once when it was not started or already joined. */
	fun join() {
		if (mStarted) mThread.join()
	}
}

/** Creates a thread that runs [block], started at once unless [start] is false. */
fun thread(start: Boolean = true, block: () -> Unit): Thread {
	val vThread = Thread(block)
	if (start) vThread.start()
	return vThread
}

// ==================
// MARK: Monitors and sleeping
// ==================

/** Runs [block] while holding the monitor of [lock]; a thread may take a monitor it already holds. */
inline fun <R> synchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)

/** Suspends the calling thread for [milliseconds]. */
fun sleep(milliseconds: Long) {
	if (milliseconds < 0L) throw IllegalArgumentException("timeout value is negative")
	java.lang.Thread.sleep(milliseconds)
}
