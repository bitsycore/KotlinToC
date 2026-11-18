@file:OptIn(ObsoleteWorkersApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package kotlin.native.concurrent

import ktc2.internal.monitorNotifyAll
import ktc2.internal.monitorWait
import ktc2.internal.ticksNanos

/*
ktc2 platform layer: Kotlin/Native's workers, which kotlinx.coroutines runs its dispatchers on. A worker is
a queue of jobs that one thread works through: one started for it ([Worker.start]), or the thread that
asked for [Worker.current], which works through its queue when it parks or processes it. What Native's
runtime does in C++ (Worker.cpp) is done here in Kotlin, over a thread of `ktc2.concurrent` and the
monitor of the queue's lock, with the same rules: the jobs in order, a delayed one put in the queue when
its time has come, a termination that waits for the delayed jobs unless told not to.
*/

// ==================
// MARK: Worker
// ==================

/** What a job of a worker's queue is. The order is Native's: a job that ran is at least [kRegular]. */
private const val kNone = 0
private const val kTerminate = 1
private const val kRegular = 2
private const val kAfter = 3

/** A job of a worker's queue: its kind, what it runs, when (for a delayed one), and the future it completes. */
private class WorkerJob(
	val kind: Int,
	val run: (() -> Any?)?,
	val future: Future<*>?,
	val due: Long = 0L,
	val sequence: Long = 0L,
	val waitsForDelayed: Boolean = false,
)

/** The workers, by their ids, and the next id. */
private object Workers {
	val lock = Any()
	val all = ArrayList<Worker>()
	var nextId = 1
	var nextSequence = 0L
	var nextFuture = 1
}

/** The worker of the thread that runs: one started for it, or the one made the first time a thread asks. */
@ThreadLocal
private var currentWorker: Worker? = null

/**
 * A queue of jobs and the thread that works through it. Kotlin/Native's is a value class of its id; this
 * one is one object for each worker, which is the same to its users: two are equal when they are the same.
 */
@ObsoleteWorkersApi
public class Worker private constructor(public val id: Int, private val mName: String?, private val mReports: Boolean) {
	/** The thread started for it, when it was started as a worker ([start]). */
	internal var thread: ktc2.concurrent.Thread? = null
	private val mLock = Any()
	private val mQueue = ArrayDeque<WorkerJob>()

	/** The delayed jobs, the one due first at the front; of two due at once, the one asked for first. */
	private val mDelayed = ArrayList<WorkerJob>()
	private var mTerminated = false

	/** True while the thread started for it runs a job: one that may never end (an event loop of kotlinx.coroutines). */
	private var mBusy = false

	public companion object {
		/**
		 * Starts a worker on a thread of its own. [errorReporting]: an exception that escapes a job of
		 * [executeAfter] goes to [kotlin.native.processUnhandledException]; [name]: what [Worker.name] says.
		 */
		public fun start(errorReporting: Boolean = true, name: String? = null): Worker {
			val vWorker = register(name, errorReporting)
			vWorker.thread = ktc2.concurrent.thread {
				// As on Kotlin/Native, a worker does not keep the program running.
				ktc2.internal.threadUnwaited()
				currentWorker = vWorker
				while (vWorker.processQueueElement(true) != kTerminate) {
					// Each job in turn, until the request to terminate.
				}
			}
			return vWorker
		}

		/** The worker of the calling thread, made the first time it asks when it was not started as one. */
		public val current: Worker
			get() = currentWorker ?: register(null, true).also { currentWorker = it }

		/** The workers that have not terminated. */
		@ExperimentalStdlibApi
		public val activeWorkers: List<Worker>
			get() = synchronized(Workers.lock) { Workers.all.toList() }

		private fun register(inName: String?, inReports: Boolean): Worker = synchronized(Workers.lock) {
			Worker(Workers.nextId++, inName, inReports).also { Workers.all += it }
		}
	}

	/** Its name, as given to [start], or `worker <id>`. */
	public val name: String get() = mName ?: "worker $id"

	override fun toString(): String = "Worker $name"

	/**
	 * Asks the worker to end, after the jobs before this request, and the delayed ones, when
	 * [processScheduledJobs]; before any other job otherwise. The future completes when it has ended.
	 */
	public fun requestTermination(processScheduledJobs: Boolean = true): Future<Unit> {
		val vFuture = Future<Unit>(synchronized(Workers.lock) { Workers.nextFuture++ })
		put(WorkerJob(kTerminate, null, vFuture, waitsForDelayed = processScheduledJobs), toFront = !processScheduledJobs)
		return vFuture
	}

	/** Runs [producer] here, then [job] of its result on the worker; the future has what [job] returns. [mode] changes nothing. */
	public fun <T1, T2> execute(mode: TransferMode, producer: () -> T1, job: (T1) -> T2): Future<T2> {
		val vArgument = producer()
		val vFuture = Future<T2>(synchronized(Workers.lock) { Workers.nextFuture++ })
		put(WorkerJob(kRegular, { job(vArgument) }, vFuture), toFront = false)
		return vFuture
	}

	/** Runs [operation] on the worker after [afterMicroseconds], at once for 0. */
	public fun executeAfter(afterMicroseconds: Long = 0, operation: () -> Unit) {
		if (afterMicroseconds < 0) throw IllegalArgumentException("Timeout parameter must be non-negative")
		val vRun: () -> Any? = { operation() }
		if (afterMicroseconds == 0L) {
			put(WorkerJob(kAfter, vRun, null), toFront = false)
			return
		}
		val vSequence = synchronized(Workers.lock) { Workers.nextSequence++ }
		val vJob = WorkerJob(kAfter, vRun, null, due = ticksNanos() + clamped(afterMicroseconds) * 1000L, sequence = vSequence)
		synchronized(mLock) {
			if (mTerminated) throw IllegalStateException("Worker is already terminated")
			var vAt = mDelayed.size
			while (vAt > 0 && mDelayed[vAt - 1].due > vJob.due) vAt--
			mDelayed.add(vAt, vJob)
			monitorNotifyAll(mLock)
		}
	}

	/** Runs one job of the queue, if there is one ready (a delayed one waits for its time). True when one ran. */
	public fun processQueue(): Boolean {
		checkCurrent()
		return processQueueElement(false) >= kRegular
	}

	/**
	 * Waits until a job arrives or [timeoutMicroseconds] have passed (forever for -1). With [process], runs
	 * what arrived and says whether a job ran; without, says whether one arrived.
	 */
	public fun park(timeoutMicroseconds: Long, process: Boolean = false): Boolean {
		if (timeoutMicroseconds < -1) throw IllegalArgumentException()
		checkCurrent()
		synchronized(mLock) {
			if (mTerminated) return false
			val vDeadline = if (timeoutMicroseconds < 0) -1L else ticksNanos() + clamped(timeoutMicroseconds) * 1000L
			val vArrived = waitForQueue(vDeadline)
			if (!process || !vArrived) return vArrived
		}
		return processQueueElement(false) >= kRegular
	}

	private fun checkCurrent() {
		if (currentWorker !== this) throw IllegalStateException("Worker is not current or already terminated")
	}

	private fun put(inJob: WorkerJob, toFront: Boolean) {
		synchronized(mLock) {
			if (mTerminated) throw IllegalStateException("Worker is already terminated")
			if (toFront) mQueue.addFirst(inJob) else mQueue.addLast(inJob)
			monitorNotifyAll(mLock)
		}
	}

	/** Puts the delayed jobs whose time has come at the end of the queue. Holding [mLock]. */
	private fun moveDue() {
		val vNow = ticksNanos()
		while (mDelayed.isNotEmpty() && mDelayed[0].due <= vNow) mQueue.addLast(mDelayed.removeAt(0))
	}

	/** Waits, holding [mLock], until the queue has a job or [inDeadline] (ticks) has passed (never for -1). */
	private fun waitForQueue(inDeadline: Long): Boolean {
		while (true) {
			if (mTerminated) return false
			moveDue()
			if (mQueue.isNotEmpty()) return true
			val vNow = ticksNanos()
			var vWait = if (mDelayed.isEmpty()) -1L else mDelayed[0].due - vNow
			if (inDeadline >= 0) {
				val vLeft = inDeadline - vNow
			if (mTerminated) return false
				if (vLeft <= 0) return false
				if (vWait < 0 || vLeft < vWait) vWait = vLeft
			}
			monitorWait(mLock, vWait)
		}
	}

	/** Takes the next job, waiting for one when [inBlocking], and runs it; what kind of job it was. */
	internal fun processQueueElement(inBlocking: Boolean): Int {
		if (mTerminated) return kTerminate
		val vJob = synchronized(mLock) {
			if (mQueue.isEmpty() && !inBlocking) return kNone
			if (!waitForQueue(-1L)) return kTerminate
			mQueue.removeFirst().also { if (inBlocking && it.kind != kTerminate) mBusy = true }
		}
		try {
			return run(vJob, inBlocking)
		} finally {
			if (inBlocking) synchronized(mLock) { mBusy = false }
		}
	}

	private fun run(vJob: WorkerJob, inBlocking: Boolean): Int {
		when (vJob.kind) {
			kTerminate -> {
				if (vJob.waitsForDelayed) {
					val vHasDelayed = synchronized(mLock) {
						val vAny = mDelayed.isNotEmpty()
						if (vAny && inBlocking) waitForQueue(-1L)
						vAny
					}
					if (vHasDelayed) {
						put(vJob, toFront = false)
						return kNone
					}
				}
				synchronized(mLock) { mTerminated = true }
				synchronized(Workers.lock) { Workers.all.remove(this) }
				vJob.future!!.complete(Unit, true)
			}
			kAfter -> try {
				vJob.run!!()
			} catch (vError: Throwable) {
				if (mReports) kotlin.native.processUnhandledException(vError)
			}
			kRegular -> {
				var vResult: Any? = null
				var vDone = true
				try {
					vResult = vJob.run!!()
				} catch (vError: Throwable) {
					vDone = false
					if (mReports) println("Uncaught exception in the job of $this: $vError")
				}
				vJob.future!!.complete(vResult, vDone)
			}
		}
		return vJob.kind
	}

	/**
	 * At the end of the program: the jobs not run yet are dropped, and a thread started for it that waits for one ends,
	 * giving back what it holds. One in the middle of a job is left to end with the program, as on Kotlin/Native.
	 */
	internal fun endAtExit() {
		val vThread = synchronized(mLock) {
			mDelayed.clear()
			mQueue.clear()
			if (mBusy) return
			mTerminated = true
			monitorNotifyAll(mLock)
			thread
		} ?: return
		thread = null
		vThread.join()
	}

	/** [inMicroseconds], at most some 115 days, as Native cuts it: no sum with it overflows. */
	private fun clamped(inMicroseconds: Long): Long = if (inMicroseconds > 10_000_000_000_000L) 10_000_000_000_000L else inMicroseconds
}

/** Ends every worker when `main` has returned (`ktc2.internal.endWorkers`): Kotlin/Native's do not keep a program running. */
internal fun endWorkersAtExit() {
	val vWorkers = synchronized(Workers.lock) { Workers.all.toList() }
	vWorkers.forEach { it.endAtExit() }
	currentWorker = null
}

/** Runs [block] with a worker started for it, terminated after. */
@ObsoleteWorkersApi
public inline fun <R> withWorker(name: String? = null, errorReporting: Boolean = true, block: Worker.() -> R): R {
	val vWorker = Worker.start(errorReporting, name)
	try {
		return vWorker.block()
	} finally {
		vWorker.requestTermination().result
	}
}

// ==================
// MARK: Future
// ==================

/** What a [Future] is at. */
@ObsoleteWorkersApi
public enum class FutureState(public val value: Int) {
	INVALID(0),
	SCHEDULED(1),
	COMPUTED(2),
	CANCELLED(3),
	THROWN(4),
}

/** How an object goes to another worker: the same either way, as on Kotlin/Native's memory manager. */
@ObsoleteWorkersApi
public enum class TransferMode(public val value: Int) {
	SAFE(0),
	UNSAFE(1),
}

/** The result of a job of a worker, once it is there: [result] waits for it. */
@ObsoleteWorkersApi
public class Future<T> internal constructor(public val id: Int) {
	private val mLock = Any()
	private var mState = FutureState.SCHEDULED
	private var mValue: Any? = null

	public val state: FutureState get() = synchronized(mLock) { mState }

	/** Waits for the result, and gives it to [code]; throws when the job threw or was cancelled. */
	public fun <R> consume(code: (T) -> R): R {
		val vValue = synchronized(mLock) {
			while (mState == FutureState.SCHEDULED) monitorWait(mLock, -1L)
			// Consumed, a future is no more (Native forgets it): INVALID from then on.
			val vState = mState
			mState = FutureState.INVALID
			when (vState) {
				FutureState.COMPUTED -> mValue.also { mValue = null }
				FutureState.CANCELLED -> throw IllegalStateException("Future is cancelled")
				FutureState.THROWN -> throw IllegalStateException("Job has thrown an exception")
				else -> throw IllegalStateException("Future is in an invalid state")
			}
		}
		@Suppress("UNCHECKED_CAST")
		return code(vValue as T)
	}

	/** The result of the job, once it is there. */
	public val result: T get() = consume { it }

	internal fun complete(inValue: Any?, inDone: Boolean) {
		synchronized(mLock) {
			mValue = inValue
			mState = if (inDone) FutureState.COMPUTED else FutureState.THROWN
			monitorNotifyAll(mLock)
		}
	}

	override fun toString(): String = "future $id"
}
