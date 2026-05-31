package NoinlineClosureTest

// `noinline` on an inline function's lambda parameter opts that parameter OUT of inline expansion: it
// becomes a real (frame-bound) capture closure — a functor the body can call, store, and pass around —
// exactly like a non-inline function's closure parameter. Because it isn't inlined, the lambda must
// capture its enclosing values explicitly with capture(...). main returns non-zero on failure.

// Mixed: `transform` is inlined in place (no capture needed); `pre`/`post` are noinline closures.
inline fun pipeline(x: Int, noinline pre: (Int) -> Int, transform: (Int) -> Int, noinline post: (Int) -> Int): Int {
	// pre/post are closures here; transform is expanded inline.
	val first  = pre(x)
	val mid    = transform(first)
	return post(mid)
}

// The noinline closure is moved to a local and invoked from there.
inline fun applyMoved(noinline f: (Int) -> Int, x: Int): Int {
	val g = f
	return g(g(x))
}

fun main(): Int {
	val base = 10
	val mul  = 3

	// pre captures base (closure), transform is inlined (no capture), post captures mul (closure).
	// pre: 5+10=15 ; transform: 15*2=30 (inlined) ; post: 30+3=33
	val r = pipeline(5,
		{ n -> capture(base); n + base },
		{ n -> n * 2 },
		{ n -> capture(mul); n + mul })
	if (r != 33) { println("FAIL pipeline: $r"); return 1 }

	// Moved noinline closure: ((4+10)+10) = 24
	val m = applyMoved({ n -> capture(base); n + base }, 4)
	if (m != 24) { println("FAIL applyMoved: $m"); return 2 }

	println("NoinlineClosureTest OK: pipeline=$r applyMoved=$m")
	return 0
}
