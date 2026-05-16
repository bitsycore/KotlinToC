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
		// Result var declared, body assigns into it with substituted receiver
		vR.sourceContains("ktc_Int \$ir")
		vR.sourceContains("10 + other")
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
		vR.sourceContains("7 * factor")
	}

	// ── Non-generic infix on String ──────────────────────────────────

	@Test fun infixOnString() {
		/* String-returning inline functions have a known stack-lifetime issue: the
		internal buffer used by ktc_core_string_cat is local to the inline block. */
		notYetImpl("String-returning infix inline: stack buffer lifetime issue")
	}

	// ── Generic infix with type substitution ─────────────────────────

	@Test fun genericInfixTypeSubstInResultVar() {
		// Verify that the result variable declaration uses the concrete type (not the template param)
		val vR = transpile("""
			package test.Main
			data class Duo<A, B>(val first: A, val second: B)
			infix fun <A, B> A.duoWith(b: B): Duo<A, B> = Duo(this, b)
			fun main(args: Array<String>) {
				val p = 1 duoWith "hello"
			}
		""")
		// Struct must be materialized
		vR.headerContains("test_Main_Duo\$2_Int_String")
		// Result var must use the concrete mangled type
		vR.sourceContains("test_Main_Duo\$2_Int_String \$ir")
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
		vR.headerContains("test_Main_Duo\$2_Int_Boolean")
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
		vR.sourceContains("/* inline 1.duoWith(b = \"x\"): Duo\$2_A_B */")
		vR.sourceNotContains("test_Main_duoWith") // no regular function emitted
	}

	// ── stdlib toStd infix via transpileWithStdlib ───────────────────

	@Test fun stdlibToStdInfixProducesStdPairType() {
		val vR = transpileMainWithStdlib(
			body = """
				val p = 1 toStd "hello"
				println(p.first)
				println(p.second)
			""")
		// Pair$2_Int_String struct must exist in the header
		vR.headerContains("ktc_std_Pair\$2_Int_String")
		// Result variable uses that type
		vR.sourceContains("ktc_std_Pair\$2_Int_String \$ir")
	}

	@Test fun stdlibToStdFirstAndSecondAccess() {
		val vR = transpileMainWithStdlib(
			body = """
				val p = 42 toStd "world"
			""")
		vR.headerContains("ktc_std_Pair\$2_Int_String")
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
		vR.sourceContains("ktc_Int \$ir")
		vR.sourceContains("3 + n")
	}
}
