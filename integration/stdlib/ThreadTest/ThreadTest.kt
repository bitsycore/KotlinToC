package ThreadTest

// Exercises the ktc.std Thread API closure form: spawn 4 OS threads via `thread { capture(ctx); ... }`,
// each incrementing a shared counter 1000× under a Mutex, join them, and assert no updates were lost
// (4 × 1000 = 4000). `ctx` is a Ref<Ctx>, so capture passes the pointer — the threads share one Ctx.
// The captured context lives on main's stack, so we join() before main returns (C-style). main returns
// a non-zero exit code on failure.

class Ctx(var counter: Int, val lock: Mutex) {
	override fun dispose() {
		lock.destroy()
	}
}

// 1000 increments under the lock. Inline, so it expands inside each generated thread entry.
fun bump(ctx: Ref<Ctx>) {
	var i = 0
	while (i < 1000) {
		ctx.lock.withLock {
			ctx.refValue.counter++
		}
		i++
	}
}

fun main(): Int {
	val ctx = Ctx(0, Mutex()).allocWith(Heap)

	// thread { capture(...) } — closure form. ctx (a Ref) is shared by pointer across all four threads.
	val t0 = thread { capture(ctx); bump(ctx) }
	val t1 = thread { capture(ctx); bump(ctx) }
	val t2 = thread { capture(ctx); bump(ctx) }
	val t3 = thread { capture(ctx); bump(ctx) }

	if (!t0.isAlive || !t1.isAlive || !t2.isAlive || !t3.isAlive) {
		println("ThreadTest FAILED: a thread failed to start")
		return 2
	}

	t0.join()
	t1.join()
	t2.join()
	t3.join()

	// Value-returning withLock (R = Int): read the final counter under the lock.
	val total = ctx.lock.withLock { ctx.counter }

	ctx.dispose()
	Heap.freeMem(ctx)

	if (total != 4000) {
		println("ThreadTest FAILED: counter=$total (expected 4000)")
		return 1
	}
	println("ThreadTest OK: counter=$total")
	return 0
}
