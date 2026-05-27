package com.bitsycore.ktc

import kotlin.test.Test

class UnionUnitTest : TranspilerTestBase() {

	@Test fun unionTypedefEmitted() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val u: Union<Int, Float> = Union1<Int, Float>(42)
				println(u.id)
			}
		""")
		r.headerContains("ktc_Union_ktc_Int_ktc_Float")
		r.headerContains("union {")
	}

	@Test fun unionConstruction() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val u1 = Union1<Int, Float>(42)
				val u2 = Union2<Int, Float>(3.14f)
				println(u1.id)
				println(u2.id)
			}
		""")
		r.sourceContains("._0 = 42")
		r.sourceContains("._1 = 3.14f")
	}

	@Test fun unionGetAccess() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val u = Union1<Int, Float>(42)
				val v: Int = u.get1()
				println(v)
			}
		""")
		r.sourceContains(".data._0")
	}

	@Test fun unionIsCheck() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val u = Union1<Int, Float>(42)
				if (u.is1()) {
					println(u.get1())
				}
			}
		""")
		r.sourceContains(".id == 0")
		r.sourceContains(".data._0")
	}

	@Test fun unionThreeMembers() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val u = Union3<Int, Float, Boolean>(true)
				if (u.is3()) {
					println(u.get3())
				}
			}
		""")
		r.headerContains("ktc_Union_ktc_Int_ktc_Float_ktc_Bool")
		r.sourceContains(".id == 2")
		r.sourceContains(".data._2")
	}

	@Test fun unionAsReturnType() {
		val r = transpile("""
			package test.Main
			fun makeUnion(flag: Boolean): Union<Int, Float> {
				if (flag) return Union1<Int, Float>(42)
				return Union2<Int, Float>(3.14f)
			}
			fun main(args: Array<String>) {
				val u = makeUnion(true)
				println(u.id)
			}
		""")
		r.sourceContains("ktc_Union_ktc_Int_ktc_Float")
	}

	@Test fun unionAsParameter() {
		val r = transpile("""
			package test.Main
			fun printUnion(u: Union<Int, Float>) {
				if (u.is1()) {
					println(u.get1())
				} else {
					println(u.get2())
				}
			}
			fun main(args: Array<String>) {
				printUnion(Union1<Int, Float>(42))
			}
		""")
		r.sourceContains("ktc_Union_ktc_Int_ktc_Float")
	}
}
