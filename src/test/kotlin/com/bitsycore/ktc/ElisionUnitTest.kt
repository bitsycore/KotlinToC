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
}
