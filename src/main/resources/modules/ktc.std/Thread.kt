package ktc.std

// ==================
// MARK: Thread
// ==================

/*
A handle to an OS thread, modelled on kotlin's `Thread`.

Construct one with the work it should run, then `start()` it:

    val t = Thread(::entry, arg)
    t.start()
    t.join()

`entry` is a top-level `fun (AnyPtr) -> Unit` and `arg` is an opaque pointer handed to it. Because KTC
lambdas are inline-only (no closures), the entry must be a function reference, not a capturing lambda —
pass everything the thread needs through `arg` (typically a heap-allocated context via
`ctx.allocWith(Heap)`, recovered inside the entry with `arg.cast<Ref<Ctx>>()`).

For the common "construct and start in one go" case, use the top-level `thread(::entry, arg)` (mirrors
kotlin.concurrent.thread). Call `join()` to block until the thread finishes; it also frees the handle.
`Thread.sleep(ms)` / `Thread.yield()` act on the current thread.
*/
class Thread(private val entry: (AnyPtr) -> Unit, private val arg: AnyPtr) {

	private var handle: AnyPtr = C.NULL

	/** True once the thread has been started and not yet joined. */
	val isAlive: Boolean get() = handle != C.NULL

	/** Start the thread. Returns true on success, false if it was already started or the OS refused. */
	fun start(): Boolean {
		if (handle != C.NULL) return false
		handle = C.ktc_core_thread_start(entry, arg)
		return handle != C.NULL
	}

	/** Block until the thread finishes and free its handle. Returns true on success. */
	fun join(): Boolean {
		if (handle == C.NULL) return false
		val vRc = C.ktc_core_thread_join(handle)
		handle = C.NULL
		return vRc == 0
	}

	companion object {

		/** Sleep the current thread for [millis] milliseconds (no-op for non-positive values). */
		fun sleep(millis: Int): Unit { C.ktc_core_thread_sleep_ms(millis) }

		/** Hint the scheduler to yield the current thread's remaining timeslice. */
		fun yield(): Unit { C.ktc_core_thread_yield() }
	}
}

/*
Construct a thread running [entry] with [arg] and (by default) start it immediately, returning the
handle — the closure-free analogue of kotlin.concurrent.thread. Pass `start = false` to defer the
`start()` call to the caller. Check the returned `Thread.isAlive` to confirm it started.
*/
fun thread(entry: (AnyPtr) -> Unit, arg: AnyPtr, start: Boolean = true): Thread {
	val vThread = Thread(entry, arg)
	if (start) vThread.start()
	return vThread
}

// ==================
// MARK: Mutex
// ==================

/*
A mutual-exclusion lock backed by a native mutex (Win32 CRITICAL_SECTION / pthread_mutex).
Guard a critical section with `withLock { … }` (preferred), or pair `lock()` … `unlock()` manually,
and release the OS resource with `destroy()` when the mutex is no longer needed.
*/
class Mutex {

	private var handle: AnyPtr = C.ktc_core_mutex_create()

	/** Acquire the lock, blocking until it is available. */
	fun lock(): Unit { if (handle != C.NULL) C.ktc_core_mutex_lock(handle) }

	/** Release the lock. */
	fun unlock(): Unit { if (handle != C.NULL) C.ktc_core_mutex_unlock(handle) }

	/** Free the native mutex. The mutex must not be used after this. */
	fun destroy(): Unit {
		if (handle != C.NULL) { C.ktc_core_mutex_destroy(handle); handle = C.NULL }
	}
}

/*
Run [block] while holding the lock, releasing it afterwards, and return the block's result — the
idiomatic kotlin way to guard a critical section. Inline, so there is no call overhead and the block
may return a value.
*/
inline fun <R> Mutex.withLock(block: () -> R): R {
	this.lock()
	val vResult = block()
	this.unlock()
	return vResult
}
