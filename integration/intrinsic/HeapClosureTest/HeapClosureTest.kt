package HeapClosureTest

// Heap-promoted closures (Phase-2 groundwork): `closure.copyWith(allocator)` copies a frame-bound functor
// to the heap and returns a Ref<Closure_N> — a real pointer the program owns and frees explicitly, like
// any other allocator-backed value. The result is callable directly (g(x) → Closure_N_invoke(g, x), no &).
// A closure that captured a stack local by reference (capture(x.asRef())) is refused — its address would
// dangle once promoted. main returns non-zero on failure.

fun main(): Int {
	val base = 10

	// Promote to the heap, call through the pointer, free.
	val c = { x: Int -> capture(base); x + base }
	val g = c.copyWith(Heap)                       // Ref<Closure_N>
	if (g(5) != 15) { println("FAIL g(5): ${g(5)}"); Heap.freeMem(g); return 1 }
	Heap.freeMem(g)

	// Reuse a heap closure across a loop, then free once.
	val mul = 3
	val scale = { n: Int -> capture(mul); n * mul }
	val h = scale.copyWith(Heap)
	var sum = 0
	for (i in 1..4) { sum += h(i) }                // 3+6+9+12 = 30
	Heap.freeMem(h)
	if (sum != 30) { println("FAIL sum: $sum"); return 2 }

	// A non-capturing closure promotes too (empty capture struct).
	val add1 = { n: Int -> n + 1 }
	val a = add1.copyWith(Heap)
	val r = a(a(7))                                // 9
	Heap.freeMem(a)
	if (r != 9) { println("FAIL add1: $r"); return 3 }

	println("HeapClosureTest OK: g=15 sum=$sum add1=$r")
	return 0
}
