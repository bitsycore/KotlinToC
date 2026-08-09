package ClosureHigherOrderTest

// Robustness of the higher-order closure path (per-closure-type monomorphization):
//  - a closure-typed local (val g = { … }) passed to a higher-order function (not just a literal lambda)
//  - a function with MULTIPLE function-typed params, each receiving its own closure
//  - an OVERLOADED higher-order function resolved per the call's arg shape
//  - the same callee monomorphized for two different closure types from two call sites
// All closures capture explicitly with capture(...) and are frame-bound. main returns non-zero on failure.

// Single closure param - used both with a literal lambda and with a closure-typed var.
fun applyTwice(f: (Int) -> Int, x: Int): Int = f(f(x))

// Two distinct function-typed params: each is retyped to its own functor struct.
fun combine(f: (Int) -> Int, g: (Int) -> Int, x: Int): Int = f(x) + g(x)

// Overloaded higher-order callee: resolved by arg shape (arity) at the call site.
fun pick(f: (Int) -> Int): Int = f(10)
fun pick(base: Int, f: (Int) -> Int): Int = f(base)

fun main(): Int {
	val k = 3

	// 1) Closure-typed var passed to a higher-order function (arg is a NameExpr, not a literal lambda).
	val addK: (Int) -> Int = { n -> capture(k); n + k }
	val viaVar = applyTwice(addK, 5)                 // (5+3)+3 = 11
	if (viaVar != 11) { println("FAIL viaVar: $viaVar"); return 1 }

	// 2) Same callee, literal lambda, different closure type → a second monomorphization.
	val mul = 4
	val viaLit = applyTwice({ n -> capture(mul); n * mul }, 2)   // (2*4)*4 = 32
	if (viaLit != 32) { println("FAIL viaLit: $viaLit"); return 2 }

	// 3) Multiple closure params in one call, two distinct functor types.
	val both = combine({ a -> capture(k); a + k }, { b -> capture(mul); b * mul }, 5)  // (5+3) + (5*4) = 28
	if (both != 28) { println("FAIL combine: $both"); return 3 }

	// 4) Overload resolution: 1-arg form vs 2-arg form, each monomorphized.
	val p1 = pick({ n -> capture(k); n + k })        // f(10) = 13
	if (p1 != 13) { println("FAIL pick/1: $p1"); return 4 }
	val p2 = pick(100, { n -> capture(mul); n + mul })   // f(100) = 104
	if (p2 != 104) { println("FAIL pick/2: $p2"); return 5 }

	println("ClosureHigherOrderTest OK: viaVar=$viaVar viaLit=$viaLit combine=$both pick1=$p1 pick2=$p2")
	return 0
}
