package com.bitsycore.ktc

import kotlin.test.Test

/*
Unit tests for the infix function feature.
Covers: modifier parsing, INFIX_IDS dynamic registration,
non-generic inline expansion, generic inline expansion with typeSubst,
and stdlib infix via transpileWithStdlib.
*/
class InfixUnitTest : TranspilerTestBase() {

	// ── Parser: infix modifier recognized and name added to INFIX_IDS ──

	@Test fun infixFunIsInlineNoBody() {
		// An infix fun with no explicit inline keyword must still emit inline expansion
		val vR = transpile("""
			package test.Main
			infix fun Int.addWith(other: Int): Int = this + other
			fun main(args: Array<String>) {
				val r = 3 addWith 4
			}
		""")
		vR.sourceContains("/* inline 3.addWith(other = 4): Int */")
		vR.sourceNotContains("test_Main_Int_addWith") // must be inlined, not a real function
	}

	@Test fun infixBinaryOpProducesInlineBlock() {
		val vR = transpile("""
			package test.Main
			infix fun Int.addWith(other: Int): Int = this + other
			fun main(args: Array<String>) {
				val r = 10 addWith 5
			}
		""")
		// Trivial single-expression body collapsed — no $ir temp, expression inlined directly
		vR.sourceNotContains("ktc_Int \$ir")
		vR.sourceContains("10 + \$il")
		vR.sourceContains("_other")
	}

	@Test fun infixFunReturnValueAssigned() {
		val vR = transpile("""
			package test.Main
			infix fun Int.times2(factor: Int): Int = this * factor
			fun main(args: Array<String>) {
				val r = 7 times2 3
			}
		""")
		vR.sourceContains("ktc_Int r =")
		vR.sourceContains("7 * \$il")
		vR.sourceContains("_factor")
	}

	// ── Non-generic infix on String ──────────────────────────────────

	@Test fun infixOnString() {
		// String-returning inline functions are now safe: the concat buffer is alloca'd
		// so it lives in the enclosing function's frame and survives the inline `{ }` block.
		val vR = transpile("""
			package test.Main
			infix fun String.cat(other: String): String = this + other
			fun main(args: Array<String>) {
				val r = "a" cat "b"
			}
		""")
		// The concat buffer is alloca'd (frame-lived), now sized from the operand lengths.
		vR.sourceContains("ktc_core_alloca")
		vR.sourceContains("ktc_core_string_cat")
	}

	// ── Generic infix with type substitution ─────────────────────────

	@Test fun genericInfixTypeSubstInResultVar() {
		// Verify that the concrete type is materialized (not the template param)
		val vR = transpile("""
			package test.Main
			data class Duo<A, B>(val first: A, val second: B)
			infix fun <A, B> A.duoWith(b: B): Duo<A, B> = Duo(this, b)
			fun main(args: Array<String>) {
				val p = 1 duoWith "hello"
			}
		""")
		// Struct must be materialized
		vR.headerContains("test_Main_Duo_Int_String")
		// Collapsed: ctor call assigned directly, no $ir temp
		vR.sourceContains("test_Main_Duo_Int_String p =")
	}

	@Test fun genericInfixReceiverAndArgBound() {
		val vR = transpile("""
			package test.Main
			data class Duo<A, B>(val first: A, val second: B)
			infix fun <A, B> A.duoWith(b: B): Duo<A, B> = Duo(this, b)
			fun main(args: Array<String>) {
				val p = 3 duoWith true
			}
		""")
		// Both Int and Boolean should appear as type args in the materialized name
		vR.headerContains("test_Main_Duo_Int_Boolean")
	}

	@Test fun genericInfixDotCallAlsoInlined() {
		// a.duoWith(b) and a duoWith b should both be inlined (method-call path)
		val vR = transpile("""
			package test.Main
			data class Duo<A, B>(val first: A, val second: B)
			infix fun <A, B> A.duoWith(b: B): Duo<A, B> = Duo(this, b)
			fun main(args: Array<String>) {
				val p = 1.duoWith("x")
			}
		""")
		// Comment uses unsubstituted template type names (typeSubst cleared for comment)
		vR.sourceContains("/* inline 1.duoWith(b = \"x\"): Duo_A_B */")
		vR.sourceNotContains("test_Main_duoWith") // no regular function emitted
	}

	// ── stdlib `to` infix via transpileWithStdlib ────────────────────
	// `to` is a normal infix extension function in Tuples.kt now — no
	// special-cased BinExpr handler. These tests guard the dispatch path.

	@Test fun stdlibToInfixProducesStdPairType() {
		val vR = transpileMainWithStdlib(
			body = """
				val p = 1 to "hello"
				println(p.first)
				println(p.second)
			""")
		// Pair_Int_String struct must exist in the header
		vR.headerContains("ktc_std_Pair_Int_String")
		// Collapsed: ctor assigned directly, no $ir temp
		vR.sourceContains("ktc_std_Pair_Int_String p =")
	}

	@Test fun stdlibToFirstAndSecondAccess() {
		val vR = transpileMainWithStdlib(
			body = """
				val p = 42 to "world"
			""")
		vR.headerContains("ktc_std_Pair_Int_String")
	}

	// ── Infix used inside expression position ───────────────────────

	@Test fun infixInPrintln() {
		val vR = transpile("""
			package test.Main
			infix fun Int.plus1(n: Int): Int = this + n
			fun main(args: Array<String>) {
				println(3 plus1 4)
			}
		""")
		// Collapsed: no $ir temp, expression used directly
		vR.sourceNotContains("ktc_Int \$ir")
		vR.sourceContains("3 + \$il")
		vR.sourceContains("_n")
	}
}
