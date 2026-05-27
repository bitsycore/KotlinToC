package com.bitsycore.ktc

import kotlin.test.Test

class ResultUnitTest : TranspilerTestBase() {

	@Test fun resultSuccessAccess() {
		val r = transpileWithStdlib("""
			package test.Main
			fun main(args: Array<String>) {
				val r: Result<Int> = Result.Success<Int>(42)
				if (r is Result.Success) {
					println(r.value)
				}
			}
		""")
		r.sourceContains("42")
	}

	@Test fun resultFailureAccess() {
		val r = transpileWithStdlib("""
			package test.Main
			fun main(args: Array<String>) {
				val r: Result<Int> = Result.Failure<Int>(1)
				if (r is Result.Failure) {
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
				if (b == 0) return Result.Failure<Int>(1)
				return Result.Success<Int>(a / b)
			}
			fun main(args: Array<String>) {
				val r = divide(10, 2)
				if (r is Result.Success) {
					println(r.value)
				}
			}
		""")
		r.sourceContains("divide")
	}

	@Test fun resultMultipleInstantiations() {
		val r = transpileWithStdlib("""
			package test.Main
			fun main(args: Array<String>) {
				val ri: Result<Int> = Result.Success<Int>(10)
				val rf: Result<Float> = Result.Success<Float>(3.14f)
				if (ri is Result.Success) println(ri.value)
				if (rf is Result.Success) println(rf.value)
			}
		""")
		r.sourceContains("Result")
	}
}
