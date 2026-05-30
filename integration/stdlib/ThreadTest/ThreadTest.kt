package ThreadTest

// Exercises the ktc.std Thread API: spawn 4 OS threads that each increment a shared counter 1000×
// under a Mutex, join them, and assert no updates were lost (4 × 1000 = 4000). The shared state is
// passed to each thread through a heap-allocated context (the documented Thread idiom, since KTC
// lambdas are inline-only). main returns a non-zero exit code on failure.

class Ctx(var counter: Int, val lock: Mutex)

// Thread entry — a top-level (AnyPtr) -> Unit. Recover the shared context from the opaque arg.
fun worker(arg: AnyPtr) {
	val ctx = arg.cast<Ref<Ctx>>()
	var i = 0
	while (i < 1000) {
		ctx.refValue.lock.withLock {
			ctx.refValue.counter = ctx.refValue.counter + 1
		}
		i = i + 1
	}
}

fun main(): Int {
	val ctx = Ctx(0, Mutex()).allocWith(Heap)
	val arg = ctx.cast<AnyPtr>()

	// thread(...) constructs and starts immediately (kotlin.concurrent.thread analogue).
	val t0 = thread(::worker, arg)
	val t1 = thread(::worker, arg)
	// Construct-then-start to exercise the Thread(...).start() path too.
	val t2 = Thread(::worker, arg)
	val t3 = Thread(::worker, arg)
	t2.start()
	t3.start()

	// Current-thread helpers (companion): give the workers a moment, hint a reschedule.
	Thread.yield()
	Thread.sleep(1)

	if (!t0.isAlive || !t1.isAlive || !t2.isAlive || !t3.isAlive) {
		println("ThreadTest FAILED: a thread failed to start")
		return 2
	}

	t0.join()
	t1.join()
	t2.join()
	t3.join()

	// Value-returning withLock (R = Int): read the final counter under the lock.
	val total = ctx.refValue.lock.withLock { ctx.refValue.counter }

	ctx.refValue.lock.destroy()
	Heap.freeMem(ctx)

	if (total != 4000) {
		println("ThreadTest FAILED: counter=$total (expected 4000)")
		return 1
	}
	println("ThreadTest OK: counter=$total")
	return 0
}
