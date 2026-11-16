@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ktc2.concurrent

import kotlinx.cinterop.*
import platform.posix.*

/*
The Kotlin/Native side of `ktc2.concurrent`, for building a ktc2 program with Kotlin/Native
(tools/compare.py): threads are POSIX threads, as they are in ktc2's C, and a monitor is one
mutex for the whole program (Kotlin/Native has no monitor in each object): correct, and coarser
than ktc2's and the JVM's, which only matters when several locks are taken at once.
*/

// ==================
// MARK: Thread
// ==================

/** A thread that runs [run], or the block it was made with, once started. */
open class Thread() {
	private var mBlock: (() -> Unit)? = null
	private var mStarted = false
	private var mHandle: pthread_t? = null

	constructor(block: () -> Unit) : this() {
		mBlock = block
	}

	/** What the thread does: the block it was made with. */
	open fun run() {
		mBlock?.invoke()
	}

	/** Starts the thread; once only. */
	fun start() {
		if (mStarted) throw IllegalStateException("Thread already started")
		mStarted = true
		val vSelf = StableRef.create(this)
		mHandle = memScoped {
			val vHandle = alloc<pthread_tVar>()
			val vResult = pthread_create(vHandle.ptr, null, staticCFunction { inArgument: COpaquePointer? ->
				val vRef = inArgument!!.asStableRef<Thread>()
				val vThread = vRef.get()
				vRef.dispose()
				vThread.run()
				null
			}, vSelf.asCPointer())
			if (vResult != 0) throw IllegalStateException("pthread_create failed: $vResult")
			vHandle.value
		}
	}

	/** Waits for the thread to end; at once when it was never started. */
	fun join() {
		val vHandle = mHandle ?: return
		pthread_join(vHandle, null)
		mHandle = null
	}
}

/** Makes a thread that runs [block], started unless [start] is false. */
fun thread(start: Boolean = true, block: () -> Unit): Thread {
	val vThread = Thread(block)
	if (start) vThread.start()
	return vThread
}

// ==================
// MARK: Monitors and sleeping
// ==================

/** The one mutex of the program, recursive as a monitor is. */
private val gMonitor: CPointer<pthread_mutex_t> = nativeHeap.alloc<pthread_mutex_t>().also { vMutex ->
	memScoped {
		val vAttributes = alloc<pthread_mutexattr_t>()
		pthread_mutexattr_init(vAttributes.ptr)
		pthread_mutexattr_settype(vAttributes.ptr, PTHREAD_MUTEX_RECURSIVE.convert())
		pthread_mutex_init(vMutex.ptr, vAttributes.ptr)
	}
}.ptr

/** Runs [block] holding the monitor: the program's one mutex here, whatever [lock] is. */
@Suppress("UNUSED_PARAMETER")
inline fun <R> synchronized(lock: Any, block: () -> R): R {
	enter()
	try {
		return block()
	} finally {
		leave()
	}
}

@PublishedApi
internal fun enter() {
	pthread_mutex_lock(gMonitor)
}

@PublishedApi
internal fun leave() {
	pthread_mutex_unlock(gMonitor)
}

/** Waits [milliseconds]. */
fun sleep(milliseconds: Long) {
	if (milliseconds < 0L) throw IllegalArgumentException("timeout value is negative")
	usleep((milliseconds * 1000).convert())
}
