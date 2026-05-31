package ClosureInferTest

// Closure-type inference without a variable annotation: a lambda assigned to a val infers its functor
// type from its OWN typed parameters (`{ x: Int -> … }`) plus the body's result type. The `it` shorthand
// still needs an expected type (an annotation or a higher-order param). main returns non-zero on failure.

// Higher-order callee, used below to exercise it-shorthand in argument position (expected type known).
fun applyTo(v: Int, f: (Int) -> Int): Int = f(v)

fun main(): Int {
	val base = 10

	// Un-annotated val, single typed param, Int result inferred from the body.
	val add = { x: Int -> capture(base); x + base }
	if (add(5) != 15) { println("FAIL add: ${add(5)}"); return 1 }

	// Un-annotated val, two typed params.
	val sum = { a: Int, b: Int -> capture(base); a + b + base }
	if (sum(1, 2) != 13) { println("FAIL sum: ${sum(1, 2)}"); return 2 }

	// it-shorthand with an explicit variable annotation (expected type → one param named `it`).
	val dbl: (Int) -> Int = { capture(base); it * 2 }
	if (dbl(7) != 14) { println("FAIL dbl: ${dbl(7)}"); return 3 }

	// it-shorthand in higher-order argument position (expected type from the parameter).
	val viaArg = applyTo(4, { capture(base); it + base })   // 4 + 10 = 14
	if (viaArg != 14) { println("FAIL viaArg: $viaArg"); return 4 }

	// Inferred-typed closure passed to a higher-order function.
	val byArg = applyTo(3, add)                              // add(3) = 13
	if (byArg != 13) { println("FAIL byArg: $byArg"); return 5 }

	println("ClosureInferTest OK: add=${add(5)} sum=${sum(1, 2)} dbl=${dbl(7)} viaArg=$viaArg byArg=$byArg")
	return 0
}
