package ClosureTest

// Exercises general (non-thread) capture closures: a value-position lambda assigned to a function-typed
// val is lowered to a per-lambda functor struct (its captures) + a generated invoke fn, and called via
// f(args). Captures marshal like function arguments - a value is copied, a Ref<T> passes the pointer.
// Frame-bound (the functor struct is stack-local). main returns non-zero on failure.

class Box(var n: Int)

// Higher-order: a non-inline function taking a closure param. Each call site monomorphizes this per
// the lambda's functor type; f(x) inside dispatches through the functor's invoke.
fun applyTwice(f: (Int) -> Int, x: Int): Int = f(f(x))

// Chained higher-order: outer's monomorphized body itself passes a (different) capturing lambda to
// another higher-order function. Exercises the deferred-emission fixpoint - emitting outer's instance
// queues inner's instance + a fresh closure while the pending lists are being flushed.
fun inner(g: (Int) -> Int, y: Int): Int = g(y)
fun outer(f: (Int) -> Int, x: Int): Int {
	val bump = 100
	return inner({ n -> capture(bump); n + bump }, f(x))
}

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

	// Higher-order: pass a capturing lambda to a non-inline function (monomorphized per closure type).
	val hi = applyTwice({ n -> capture(base); n + base }, 5)   // (5+10)+10 = 25
	if (hi != 25) { println("FAIL applyTwice: $hi"); return 5 }

	// Chained higher-order: the lambda passed to outer captures base; outer's body passes a second
	// (bump-capturing) lambda to inner. ((1 + base) + 100) = 111.
	val chained = outer({ m -> capture(base); m + base }, 1)
	if (chained != 111) { println("FAIL chained: $chained"); return 6 }

	println("ClosureTest OK: add(5)=${add(5)} scale(3)=${scale(3)} bump=$total applyTwice=$hi chained=$chained")
	return 0
}
