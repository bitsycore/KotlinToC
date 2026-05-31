package CaptureRefTest

// Capture modes for general closures (explicit capture(...)):
//  - capture(x)          captures x by what it already is: a value type is COPIED (snapshot at the point
//                        the closure value is built); an existing Ref<T> passes its pointer.
//  - capture(x.asRef())  captures &x as a Ref<T> — explicit by-reference capture of a value local. Inside
//                        the closure x is a Ref<T>, so reads/writes go through x.refValue and reach the
//                        original storage. Frame-bound: the address must not outlive the defining frame.
// main returns non-zero on failure.

fun main(): Int {
	// By-ref capture: the closure mutates the original value local through &counter.
	var counter = 0
	val inc: () -> Unit = { capture(counter.asRef()); counter.refValue = counter.refValue + 1 }
	inc(); inc(); inc()
	if (counter != 3) { println("FAIL inc: $counter"); return 1 }

	// Mixed: total captured by ref (written through), n captured by value (read).
	var total = 0
	val n = 5
	val addN: () -> Unit = { capture(total.asRef(), n); total.refValue = total.refValue + n }
	addN(); addN()
	if (total != 10) { println("FAIL addN: $total"); return 2 }

	// Snapshot semantics: capture(seed) copies seed at closure-build time; a later mutation of seed is
	// invisible to the closure (contrast with the by-ref form above).
	var seed = 1
	val readsSnapshot: () -> Int = { capture(seed); seed }
	seed = 99
	if (readsSnapshot() != 1) { println("FAIL snapshot: ${readsSnapshot()}"); return 3 }

	println("CaptureRefTest OK: counter=$counter total=$total snapshot=${readsSnapshot()}")
	return 0
}
