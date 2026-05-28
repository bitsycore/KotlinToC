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

	// ── E012: invalid @Size value ─────────────────────────────────────

	@Test fun zeroSizeRejected() {
		transpileExpectError("""
			package test.Main
			fun f(): @Size(0) IntArray = intArrayOf()
			fun main(args: Array<String>) { f() }
		""", "E012")
	}

	@Test fun negativeSizeRejected() {
		transpileExpectError("""
			package test.Main
			fun f(): @Size(-2) IntArray = intArrayOf()
			fun main(args: Array<String>) { f() }
		""", "E012")
	}

	@Test fun positiveSizeOk() {
		val r = transpile("""
			package test.Main
			fun f(): @Size(4) IntArray = intArrayOf(1,2,3,4)
			fun main(args: Array<String>) { val a = f(); println(a.size) }
		""")
		r.hasNoWarnings()
	}

	// ── E013: duplicate enum entry ────────────────────────────────────

	@Test fun duplicateEnumEntryRejected() {
		transpileExpectError("""
			package test.Main
			enum class Color { RED, GREEN, RED }
			fun main(args: Array<String>) { val c = Color.RED; println(c.ordinal) }
		""", "E013")
	}

	// ── E014: duplicate parameter ─────────────────────────────────────

	@Test fun duplicateFunctionParameterRejected() {
		transpileExpectError("""
			package test.Main
			fun f(x: Int, x: Int): Int = x
			fun main(args: Array<String>) { println(f(1, 2)) }
		""", "E014")
	}

	@Test fun duplicateCtorParameterRejected() {
		transpileExpectError("""
			package test.Main
			class P(val a: Int, val a: Int)
			fun main(args: Array<String>) { val p = P(1, 2); println(p.a) }
		""", "E014")
	}

	// ── operator return-type validation ──────────────────────────────

	@Test fun equalsWithWrongReturnRejected() {
		transpileExpectError("""
			package test.Main
			class Foo(val x: Int) {
				operator fun equals(other: Any?): Int = if (other is Foo) 1 else 0
			}
			fun main(args: Array<String>) { val f = Foo(1); println(f.x) }
		""", "must return Boolean")
	}

	@Test fun compareToWithWrongReturnRejected() {
		transpileExpectError("""
			package test.Main
			class Foo(val x: Int) {
				operator fun compareTo(other: Foo): Boolean = x == other.x
			}
			fun main(args: Array<String>) { val f = Foo(1); println(f.x) }
		""", "must return Int")
	}

	@Test fun compareToReturningIntOk() {
		val r = transpile("""
			package test.Main
			class Foo(val x: Int) {
				operator fun compareTo(other: Foo): Int = x - other.x
			}
			fun main(args: Array<String>) { val f = Foo(1); println(f.x) }
		""")
		r.hasNoWarnings()
	}

	// ── empty for body ────────────────────────────────────────────────

	@Test fun emptyForBodyWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				for (i in 1..5) {
				}
			}
		""")
		r.hasWarnings(1)
	}

	// ── empty when branch ─────────────────────────────────────────────

	@Test fun emptyWhenBranchBodyWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 3
				when (x) {
					1 -> println("one")
					2 -> {}
					else -> println("other")
				}
			}
		""")
		assert(r.warningCount >= 1) { "Expected at least one warning, got ${r.warningCount}" }
	}

	// ── W014 self-comparison ──────────────────────────────────────────

	@Test fun selfComparisonWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 5
				if (x == x) println("yes")
			}
		""")
		assert(r.warningCount >= 1) { "Expected at least one warning, got ${r.warningCount}" }
	}

	@Test fun floatSelfCompareNotWarned() {
		// NaN != NaN is intentional — float self-compare must not fire.
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 1.5
				if (x != x) println("nan")
			}
		""")
		r.hasNoWarnings()
	}

	// ── W015 identical branches ───────────────────────────────────────

	@Test fun identicalBranchesWarns() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 5
				if (x > 0) {
					println("hi")
				} else {
					println("hi")
				}
			}
		""")
		assert(r.warningCount >= 1) { "Expected at least one warning, got ${r.warningCount}" }
	}

	@Test fun distinctBranchesNoWarning() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 5
				if (x > 0) println("pos") else println("neg")
			}
		""")
		r.hasNoWarnings()
	}

	// ── unreachable branch after else ─────────────────────────────────

	@Test fun branchAfterElseRejected() {
		transpileExpectError("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 3
				when (x) {
					1 -> println("one")
					else -> println("else")
					2 -> println("two")
				}
			}
		""", "Unreachable 'when' branch")
	}
}
