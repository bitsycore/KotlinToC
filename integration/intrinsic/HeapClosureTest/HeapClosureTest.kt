package HeapClosureTest

// Heap closures. A frame-bound closure (val f = { … }) stays a by-value functor for inline/local use and
// can't escape. To escape — return, store, name a closure — heap-promote it with closure.copyWith(alloc),
// which yields a Ref<Closure<F>>: a single heap block (the type-erased fat pointer with its captures
// folded in). It is callable like any function (g(x)), can be returned/stored, and is freed in one call
// with freeMem(g). A closure that captured a stack local by reference (capture(x.asRef())) is refused —
// its address would dangle once promoted. main returns non-zero on failure.

// The escape the frame-bound functor can't do: return a closure that outlives the function.
fun makeAdder(base: Int): Ref<Closure<(Int) -> Int>> {
	val c = { x: Int -> capture(base); x + base }
	return c.copyWith(Heap)
}

fun main(): Int {
	val base = 10

	// Promote to the heap, call, free (inferred Ref<Closure<(Int)->Int>>).
	val c = { x: Int -> capture(base); x + base }
	val g = c.copyWith(Heap)
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

	// Returned (escaped) closure — outlives makeAdder's frame.
	val adder: Ref<Closure<(Int) -> Int>> = makeAdder(100)
	val ar = adder(5)                              // 105
	Heap.freeMem(adder)
	if (ar != 105) { println("FAIL adder: $ar"); return 3 }

	println("HeapClosureTest OK: g=15 sum=$sum adder=$ar")
	return 0
}
