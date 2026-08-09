package StringFeaturesTest

// ═══════════════════════════════════════════════════════════════════
//  Complete integration test + worked examples for the reworked String.
//
//  String is now an OWNED, NUL-terminated value that behaves like a
//  read-only Array<T>. This exercises every feature of the rework:
//   - ownership API: copy() / asRef() / copyWith() / allocWith()
//   - .cPtr for C interop (and the NUL-termination guarantee)
//   - substring + the inline slice/trim/prefix extensions (now copies)
//   - toStringMaxLen() / toStringComputeLen()
//   - templateOf("…") with .maxLen / .computeLen() / .toString()
//   - sb."…" receiver-template rendering
//   - plus the everyday ops (length, index, ==, concat, parse, toString)
//
//  Returns 0 when every check passes, 1 otherwise.
// ═══════════════════════════════════════════════════════════════════

data class Point(val x: Int, val y: Int)

// ── A tiny check harness (top-level var, written across functions) ──
var gFails = 0

fun expect(inCond: Boolean, inLabel: String) {
	if (!inCond) {
		println("  FAIL: $inLabel")
		gFails = gFails + 1
	}
}

// ══════════════════════════════════════════════════════════════════
// MARK: Example functions (each demonstrates one escape pattern)
// ══════════════════════════════════════════════════════════════════

// copyWith(Heap) heap-promotes a freshly built string → a Ref<String> that
// escapes this frame. The caller owns it and releases it with freeMem.
fun makeLabel(inName: String, inN: Int): Ref<String> {
	val vLocal = "label[$inName]=$inN"
	return vLocal.copyWith(Heap)
}

// allocWith on a literal receiver - also a heap Ref<String>.
fun makeOwned(): Ref<String> = "owned-heap-string".allocWith(Heap)

// substring copies and is frame-bound, so a function returning one must be
// inline (the copy then lands in the caller's frame).
inline fun firstSegment(inPath: String): String = inPath.substringBefore('/')

// ══════════════════════════════════════════════════════════════════
// MARK: main
// ══════════════════════════════════════════════════════════════════

fun main(): Int {

	// ── Ownership: copy() is an independent, owned duplicate ─────────
	val vSrc = "abcdef"
	val vDup = vSrc.copy()
	expect(vDup == vSrc, "copy() equals source")
	expect(vDup.length == 6, "copy() preserves length")

	// ── asRef(): a Ref<String> aliasing the same bytes (frame-bound) ─
	val vRef = vSrc.asRef()
	expect(vRef.refValue == "abcdef", "asRef().refValue reads the aliased value")

	// ── copyWith / allocWith: heap Ref<String> that escapes ─────────
	val vLabel = makeLabel("io", 3)
	expect(vLabel.refValue == "label[io]=3", "copyWith(Heap) survives the producing frame")
	Heap.freeMem(vLabel)

	val vOwned = makeOwned()
	expect(vOwned.refValue == "owned-heap-string", "allocWith(Heap) on a literal")
	Heap.freeMem(vOwned)

	// ── .cPtr + NUL termination: owned strings are C-passable ───────
	// strlen stops at the trailing '\0', so it must equal the byte length.
	val vHello = "hello".copy()
	expect(C.strlen(vHello.cPtr).toInt() == vHello.length, "owned String is NUL-terminated (strlen == length)")

	// ── substring COPIES (owned, NUL-terminated) ────────────────────
	val vText = "hello world"
	val vSub = vText.substring(0, 5)
	expect(vSub == "hello", "substring(0,5)")
	expect(C.strlen(vSub.cPtr).toInt() == 5, "substring result is NUL-terminated")
	expect(vText.substring(6) == "world", "substring(from)")

	// ── View extensions compose substring → now copies ──────────────
	expect("hello.kt".removeSuffix(".kt") == "hello", "removeSuffix")
	expect("foo_bar".removePrefix("foo_") == "bar", "removePrefix")
	expect("a/b/c".substringBefore('/') == "a", "substringBefore")
	expect("a/b/c".substringAfterLast('/') == "c", "substringAfterLast")
	expect("  trim me  ".trim() == "trim me", "trim")
	expect("hello".take(3) == "hel", "take")
	expect("hello".drop(2) == "llo", "drop")
	expect(firstSegment("usr/local/bin") == "usr", "inline fn returning a substring")

	// ── Sizing intrinsics ───────────────────────────────────────────
	expect(42.toStringComputeLen() == 2, "Int.toStringComputeLen() == len(\"42\")")
	expect((0 - 7).toStringComputeLen() == 2, "negative Int computeLen == len(\"-7\")")
	val vPt = Point(1, 20)
	expect(vPt.toStringComputeLen() == "Point(x=1, y=20)".length, "data class computeLen")
	expect(42.toStringMaxLen() >= 42.toStringComputeLen(), "maxLen is an upper bound (Int)")
	expect(vPt.toStringMaxLen() >= vPt.toStringComputeLen(), "maxLen is an upper bound (data class)")

	// ── Template: size & build without repeating the text ───────────
	val vN = 99
	val vBounded = templateOf("n=$vN")          // Int interpolation → maxLen is available
	expect(vBounded.maxLen >= vBounded.computeLen(), "template.maxLen >= computeLen")
	expect(vBounded.computeLen() == "n=99".length, "template.computeLen()")
	expect(vBounded.toString() == "n=99", "template.toString()")

	val vName = "World"
	val vUnbounded = templateOf("hi $vName")     // String interpolation → use computeLen
	expect(vUnbounded.computeLen() == "hi World".length, "unbounded template computeLen")
	expect(vUnbounded.toString() == "hi World", "unbounded template toString")

	// ── sb."…": render a template into a StringBuffer ───────────────
	val vBuf = CharArray(64)
	val vSb = StringBuffer(vBuf.asRef(), 0)
	val vRendered = vSb."Point is $vPt"
	expect(vRendered == "Point is Point(x=1, y=20)", "sb.\"…\" renders a template")

	// ── Everyday string ops (examples) ──────────────────────────────
	expect("Kotlin".length == 6, "length")
	expect("Kotlin"[0] == 'K', "indexing")
	expect("foo" + "bar" == "foobar", "concat")
	expect("hello world".startsWith("hello"), "startsWith")
	expect("hello world".contains("lo w"), "contains")
	expect("123".toInt() == 123, "toInt")
	expect("3.5".toDouble() > 3.0, "toDouble")
	expect(vPt.toString() == "Point(x=1, y=20)", "data class toString")
	expect("$vName!" == "World!", "string template")

	if (gFails != 0) {
		println("StringFeaturesTest FAIL ($gFails check(s) failed)")
		return 1
	}
	println("StringFeaturesTest OK")
	return 0
}
