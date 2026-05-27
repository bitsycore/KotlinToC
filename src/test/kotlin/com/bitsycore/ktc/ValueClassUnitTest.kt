package com.bitsycore.ktc

import kotlin.test.Test

class ValueClassUnitTest : TranspilerTestBase() {

	@Test fun valueClassResolvedToUnderlyingType() {
		val r = transpile("""
			package test.Main
			value class UserId(val raw: Int)
			fun main(args: Array<String>) {
				val id = UserId(42)
				println(id)
			}
		""")
		// value class emits as the underlying type, no struct
		r.sourceContains("ktc_Int id = 42;")
		r.sourceNotContains("KTC_CLASS")
	}

	@Test fun valueClassPropertyAccess() {
		val r = transpile("""
			package test.Main
			value class UserId(val raw: Int)
			fun main(args: Array<String>) {
				val id = UserId(42)
				println(id.raw)
			}
		""")
		// .raw on a value class is a no-op: the variable IS the raw value
		r.sourceContains("ktc_Int id = 42;")
	}

	@Test fun valueClassAsParameter() {
		val r = transpile("""
			package test.Main
			value class Meters(val value: Float)
			fun showDist(d: Meters) { println(d.value) }
			fun main(args: Array<String>) {
				showDist(Meters(3.14f))
			}
		""")
		// Parameter typed as value class should resolve to underlying type
		r.sourceContains("ktc_Float d")
	}

	@Test fun jvmInlineAnnotation() {
		val r = transpile("""
			package test.Main
			@JvmInline value class Token(val raw: String)
			fun main(args: Array<String>) {
				val t = Token("abc")
				println(t.raw)
			}
		""")
		r.sourceContains("ktc_String t =")
	}

	@Test fun valueClassMultiplePropsError() {
		transpileExpectError("""
			package test.Main
			value class Bad(val a: Int, val b: Int)
			fun main() {}
		""", "exactly one val property")
	}

	@Test fun valueClassBodyPropsError() {
		transpileExpectError("""
			package test.Main
			value class Bad(val raw: Int) {
				val extra: Int = 0
			}
			fun main() {}
		""", "cannot have body properties")
	}
}
