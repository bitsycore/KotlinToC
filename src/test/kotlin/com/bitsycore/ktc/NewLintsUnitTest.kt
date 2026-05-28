package com.bitsycore.ktc

import kotlin.test.Test

/* Tests for the new transpile-time lint warnings:
   W009 (bounds), W010 (sized-array-truncate), W011 (empty-body),
   W012 (redundant-bang), W013 (self-assign). */
class NewLintsUnitTest : TranspilerTestBase() {

	// ── W011: empty if / else / while / do-while bodies ──────────────

	@Test fun emptyIfBodyWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 5
				if (x > 0) {
				}
				println(x)
			}
		""")
		r.hasWarnings(1)
	}

	@Test fun emptyElseBodyWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 5
				if (x > 0) {
					println("pos")
				} else {
				}
			}
		""")
		r.hasWarnings(1)
	}

	@Test fun emptyWhileBodyWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				var x = 5
				while (x > 0) {
				}
				println(x)
			}
		""")
		r.hasWarnings(1)
	}

	@Test fun nonEmptyIfNoWarning() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 5
				if (x > 0) println("pos") else println("non-pos")
			}
		""")
		r.hasNoWarnings()
	}

	// ── W012: !! on literal null ──────────────────────────────────────

	@Test fun bangBangOnNullLiteralWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x: Int? = null!!
				println(x)
			}
		""")
		// Expect at least 1 warning (others might fire too, e.g. nullable inference)
		assert(r.warningCount >= 1) { "Expected at least one warning, got ${r.warningCount}" }
	}

	// ── W013: self-assignment ─────────────────────────────────────────

	@Test fun selfAssignmentWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				var x = 5
				x = x
				println(x)
			}
		""")
		r.hasWarnings(1)
	}

	@Test fun normalAssignmentNoWarning() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				var x = 5
				val y = 10
				x = y
				println(x)
			}
		""")
		r.hasNoWarnings()
	}

	@Test fun selfAssignOnFieldWarns() {
		val r = transpile("""
			package test.Main
			class Box(var v: Int)
			fun main(args: Array<String>) {
				val b = Box(1)
				b.v = b.v
				println(b.v)
			}
		""")
		r.hasWarnings(1)
	}

	// ── W009: static out-of-bounds index ──────────────────────────────

	@Test fun outOfBoundsStringLiteralWarns() {
		// staticBoundsCheck fires when the indexed expression is itself a literal,
		// so the bounds-check warning surfaces at transpile time.
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				println("abc"[5])
			}
		""")
		assert(r.warningCount >= 1) { "Expected at least one warning, got ${r.warningCount}" }
	}

	@Test fun negativeIndexWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				println("abc"[-1])
			}
		""")
		assert(r.warningCount >= 1) { "Expected at least one warning, got ${r.warningCount}" }
	}
}
