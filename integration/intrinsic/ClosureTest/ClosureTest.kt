package ClosureTest

// Exercises general (non-thread) capture closures: a value-position lambda assigned to a function-typed
// val is lowered to a per-lambda functor struct (its captures) + a generated invoke fn, and called via
// f(args). Captures marshal like function arguments — a value is copied, a Ref<T> passes the pointer.
// Frame-bound (the functor struct is stack-local). main returns non-zero on failure.

class Box(var n: Int)

fun main(): Int {
	// Value capture, value-returning closure.
	val base = 10
	val add: (Int) -> Int = { x -> capture(base); x + base }
	if (add(5) != 15) { println("FAIL add"); return 1 }
	if (add(0) != 10) { println("FAIL add0"); return 2 }

	// Two distinct lambdas → two distinct functor types, both capturing base by value.
	val scale: (Int) -> Int = { n -> capture(base); n * base }
	if (scale(3) != 30) { println("FAIL scale"); return 3 }

	// Ref capture: the closure mutates shared state through the captured pointer.
	val box = Box(0).allocWith(Heap)
	val bump: () -> Unit = { capture(box); box.refValue.n = box.refValue.n + 1 }
	bump(); bump(); bump()
	val total = box.refValue.n
	Heap.freeMem(box)
	if (total != 3) { println("FAIL bump: $total"); return 4 }

	println("ClosureTest OK: add(5)=${add(5)} scale(3)=${scale(3)} bump=$total")
	return 0
}
