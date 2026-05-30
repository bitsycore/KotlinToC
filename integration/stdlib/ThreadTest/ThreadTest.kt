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
		ctx.refValue.lock.lock()
		ctx.refValue.counter = ctx.refValue.counter + 1
		ctx.refValue.lock.unlock()
		i = i + 1
	}
}

fun main(): Int {
	val ctx = Ctx(0, Mutex()).allocWith(Heap)
	val arg = ctx.cast<AnyPtr>()

	val t0 = startThread(::worker, arg)
	val t1 = startThread(::worker, arg)
	val t2 = startThread(::worker, arg)
	val t3 = startThread(::worker, arg)

	if (!t0.isStarted || !t1.isStarted || !t2.isStarted || !t3.isStarted) {
		println("ThreadTest FAILED: a thread failed to start")
		return 2
	}

	t0.join()
	t1.join()
	t2.join()
	t3.join()

	ctx.refValue.lock.destroy()
	val total = ctx.refValue.counter
	Heap.freeMem(ctx)

	if (total != 4000) {
		println("ThreadTest FAILED: counter=$total (expected 4000)")
		return 1
	}
	println("ThreadTest OK: counter=$total")
	return 0
}
