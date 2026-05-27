package com.bitsycore.ktc

import kotlin.test.Test

class ElisionUnitTest : TranspilerTestBase() {

	// ── Null-check elision for .asRef() ──────────────────────────────

	@Test fun asRefRefValueSkipsNullCheck() {
		// x.asRef().refValue → &x then *p, provably non-null → no null_check
		val r = transpileMain("""
			var x = 42
			println(x.asRef().refValue)
		""")
		r.sourceNotContains("null_check")
	}

	@Test fun asRefAssignSkipsNullCheck() {
		val r = transpileMain("""
			var x = 42
			x.asRef().refValue = 99
			println(x)
		""")
		r.sourceNotContains("null_check")
	}

	// ── Trivial inline body collapse ─────────────────────────────────

	@Test fun singleExprInlineNoIrVar() {
		val r = transpile("""
			package test.Main
			inline fun square(x: Int): Int = x * x
			fun main(args: Array<String>) {
				val r = square(7)
			}
		""")
		r.sourceContains("/* inline square(x = 7): Int */")
		r.sourceNotContains("\$ir")
		r.sourceNotContains("\$end_ir")
		r.sourceContains("_x = 7;")
	}

	@Test fun multiStmtInlineKeepsIrVar() {
		val r = transpile("""
			package test.Main
			inline fun compute(x: Int): Int {
				val y = x + 1
				return y * 2
			}
			fun main(args: Array<String>) {
				val r = compute(5)
			}
		""")
		// Multi-statement body: full expansion with $ir and block
		r.sourceContains("\$ir")
		r.sourceContains("{")
	}

	// ── Constant folding ─────────────────────────────────────────────

	@Test fun constantFoldIntAdd() {
		val r = transpileMain("val x = 3 + 4\nprintln(x)")
		r.sourceContains("ktc_Int x = 7;")
	}

	@Test fun constantFoldIntMul() {
		val r = transpileMain("val x = 6 * 7\nprintln(x)")
		r.sourceContains("ktc_Int x = 42;")
	}

	@Test fun constantFoldNegativeResult() {
		val r = transpileMain("val x = 3 - 10\nprintln(x)")
		r.sourceContains("ktc_Int x = (-7);")
	}

	@Test fun nonConstantNotFolded() {
		val r = transpileMain("val a = 3\nval x = a + 4\nprintln(x)")
		r.sourceContains("(a + 4)")
	}

	@Test fun singleExprExtensionInlineCollapsed() {
		val r = transpile("""
			package test.Main
			inline fun Int.doubled(): Int = this * 2
			fun main(args: Array<String>) {
				val r = 5.doubled()
			}
		""")
		r.sourceContains("/* inline 5.doubled(): Int */")
		r.sourceNotContains("\$ir")
	}
}
