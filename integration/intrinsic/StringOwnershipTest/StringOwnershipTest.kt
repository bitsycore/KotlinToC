package StringOwnershipTest

// String ownership API — String is a read-only Array: copy / asRef / copyWith / allocWith, plus the
// Ref<String> heap form (a ktc_String value, released with freeMem) that escapes its defining frame.

// Heap-promote a freshly built string and return it (escapes makeGreeting's frame).
fun makeGreeting(name: String): Ref<String> {
	val s = "Hello, $name!"
	return s.copyWith(Heap)
}

fun main(): Int {
	var ok = true

	// copy() — an owned, independent, NUL-terminated duplicate equal to the source.
	val src = "abcdef"
	val dup = src.copy()
	ok = ok && (dup == src)
	ok = ok && (dup.length == 6)

	// asRef() — a Ref<String> aliasing the same bytes; read back via refValue (identity for value-struct refs).
	val r = src.asRef()
	ok = ok && (r.refValue == "abcdef")

	// copyWith heap escape — the returned Ref<String> outlives makeGreeting's frame.
	val g = makeGreeting("World")
	ok = ok && (g.refValue == "Hello, World!")
	Heap.freeMem(g)

	// allocWith on a literal receiver — also a heap Ref<String>.
	val h = "owned text".allocWith(Heap)
	ok = ok && (h.refValue == "owned text")
	ok = ok && (h.refValue.length == 10)
	Heap.freeMem(h)

	if (!ok) {
		println("StringOwnershipTest FAIL")
		return 1
	}
	println("StringOwnershipTest OK")
	return 0
}
