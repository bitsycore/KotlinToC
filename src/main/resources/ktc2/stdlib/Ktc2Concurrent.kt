package ktc2.concurrent

import ktc2.internal.monitorEnter
import ktc2.internal.monitorExit
import ktc2.internal.threadJoin
import ktc2.internal.threadRelease
import ktc2.internal.threadSleep
import ktc2.internal.threadStart

/*
The C side of `ktc2.concurrent`: threads of the operating system, a monitor per object, and
sleeping. Kotlin itself has these only on the JVM, under the names of the JDK.
*/

// ==================
// MARK: Thread
// ==================

/**
 * A thread of the operating system that runs [run] once started. Objects reachable from
 * several threads are counted atomically; a property written by one thread while another
 * uses it still needs [synchronized].
 */
public open class Thread() {

	private var mBlock: (() -> Unit)? = null

	/** The system's handle of the thread while it is started and not joined. */
	private val mHandle = ThreadHandle()

	/** A thread nobody joined: what the system keeps for a join is given back when this object goes. */
	private val mCleaner = ktc2.createCleaner(mHandle) { if (it.value != 0L) threadRelease(it.value) }
	private var mStarted: Boolean = false

	public constructor(block: () -> Unit) : this() {
		mBlock = block
	}

	/** What the thread does: the block it was built with, unless overridden. */
	public open fun run() {
		mBlock?.invoke()
	}

	public fun start() {
		if (mStarted) throw IllegalStateException("Thread already started")
		mStarted = true
		mHandle.value = threadStart(this)
	}

	/** Waits for the thread to end; returns at once when it was not started or already joined. */
	public fun join() {
		val vHandle = mHandle.value
		if (vHandle != 0L) {
			mHandle.value = 0L
			threadJoin(vHandle)
		}
	}
}

/** Holds the handle of a [Thread] apart from it, for the cleaner of the thread to read when the thread object is gone. */
private class ThreadHandle {
	var value: Long = 0L
}

/** Creates a thread that runs [block], started at once unless [start] is false. */
public fun thread(start: Boolean = true, block: () -> Unit): Thread {
	val vThread = Thread(block)
	if (start) vThread.start()
	return vThread
}

// ==================
// MARK: Monitors and sleeping
// ==================

/** Runs [block] while holding the monitor of [lock]; a thread may take a monitor it already holds. */
public inline fun <R> synchronized(lock: Any, block: () -> R): R {
	monitorEnter(lock)
	try {
		return block()
	} finally {
		monitorExit(lock)
	}
}

/** Suspends the calling thread for [milliseconds]. */
public fun sleep(milliseconds: Long) {
	if (milliseconds < 0L) throw IllegalArgumentException("timeout value is negative")
	threadSleep(milliseconds)
}
