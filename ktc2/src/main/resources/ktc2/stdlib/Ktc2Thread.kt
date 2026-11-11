@file:Suppress("PackageDirectoryMismatch")

package java.lang

import ktc2.internal.threadJoin
import ktc2.internal.threadStart

// ==================
// MARK: Thread
// ==================

/**
 * The part of `java.lang.Thread` programs use through `kotlin.concurrent.thread`: a thread
 * of the operating system that runs [run] once started. Objects reachable from several
 * threads are counted atomically; a property written by one thread while another uses it
 * still needs `synchronized`, as on the JVM.
 */
public open class Thread() {

	private var mBlock: (() -> Unit)? = null
	private var mHandle: kotlin.Long = 0L
	private var mStarted: kotlin.Boolean = false

	public constructor(block: () -> Unit) : this() {
		mBlock = block
	}

	/** What the thread does: the block it was built with, unless overridden. */
	public open fun run() {
		mBlock?.invoke()
	}

	public fun start() {
		if (mStarted) throw kotlin.IllegalStateException("Thread already started")
		mStarted = true
		mHandle = threadStart(this)
	}

	/** Waits for the thread to end; returns at once when it was not started or already joined. */
	public fun join() {
		val vHandle = mHandle
		if (vHandle != 0L) {
			mHandle = 0L
			threadJoin(vHandle)
		}
	}
}
