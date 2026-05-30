package ktc.std

// ==================
// MARK: Thread
// ==================

/*
A handle to a running OS thread.

Start one with `startThread(::entry, arg)` where `entry` is a top-level `fun (AnyPtr) -> Unit` and
`arg` is an opaque pointer handed to it. Because KTC lambdas are inline-only (no closures), the entry
must be a function reference, not a closure — pass everything the thread needs through `arg`
(typically a heap-allocated context via `ctx.allocWith(Heap)`, recovered inside the entry with
`arg.cast<Ref<Ctx>>()`).

Call `join()` to block until the thread finishes; it also frees the handle.
*/
class Thread(private var handle: AnyPtr) {

	/** True when the thread was started successfully (a non-null native handle). */
	val isStarted: Boolean get() = handle != C.NULL

	/** Block until the thread finishes and free its handle. Returns true on success. */
	fun join(): Boolean {
		if (handle == C.NULL) return false
		val vRc = C.ktc_core_thread_join(handle)
		handle = C.NULL
		return vRc == 0
	}
}

/** Start a new OS thread running [entry] with [arg]. Check the returned `Thread.isStarted`. */
fun startThread(entry: (AnyPtr) -> Unit, arg: AnyPtr): Thread =
	Thread(C.ktc_core_thread_start(entry, arg))

/** Sleep the current thread for [millis] milliseconds (no-op for non-positive values). */
fun sleepThread(millis: Int) { C.ktc_core_thread_sleep_ms(millis) }

/** Hint the scheduler to yield the current thread's remaining timeslice. */
fun yieldThread() { C.ktc_core_thread_yield() }

// ==================
// MARK: Mutex
// ==================

/*
A mutual-exclusion lock backed by a native mutex (Win32 CRITICAL_SECTION / pthread_mutex).
Guard a critical section with `lock()` … `unlock()`, and release the OS resource with `destroy()`
when the mutex is no longer needed.
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
