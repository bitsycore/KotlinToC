package com.bitsycore.ktc

import kotlin.test.Test

class ResultUnitTest : TranspilerTestBase() {

	@Test fun resultSuccessAccess() {
		val r = transpileWithStdlib("""
			package test.Main
			fun main(args: Array<String>) {
				val r = Result<Int>(42, 0)
				if (r.isSuccess) {
					println(r.value)
				}
			}
		""")
		r.sourceContains("42")
		r.sourceContains("errorCode")
	}

	@Test fun resultFailureAccess() {
		val r = transpileWithStdlib("""
			package test.Main
			fun main(args: Array<String>) {
				val r = Result<Int>(0, 1)
				if (r.isFailure) {
					println(r.errorCode)
				}
			}
		""")
		r.sourceContains("errorCode")
	}

	@Test fun resultAsReturnType() {
		val r = transpileWithStdlib("""
			package test.Main
			fun divide(a: Int, b: Int): Result<Int> {
				if (b == 0) return Result<Int>(0, 1)
				return Result<Int>(a / b, 0)
			}
			fun main(args: Array<String>) {
				val r = divide(10, 2)
				println(r.value)
			}
		""")
		r.sourceContains("divide")
	}

	@Test fun resultMultipleInstantiations() {
		val r = transpileWithStdlib("""
			package test.Main
			fun main(args: Array<String>) {
				val ri = Result<Int>(10, 0)
				val rf = Result<Float>(3.14f, 0)
				println(ri.value)
				println(rf.value)
			}
		""")
		r.sourceContains("Result_Int")
		r.sourceContains("Result_Float")
	}
}
