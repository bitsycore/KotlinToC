package com.bitsycore.ktc

import kotlin.test.Test

class ReifiedUnitTest : TranspilerTestBase() {

	@Test fun reifiedTypeParamAccepted() {
		val r = transpile("""
			package test.Main
			inline fun <reified T> typeName(): String = T::class.simpleName
			fun main(args: Array<String>) {
				println(typeName<Int>())
			}
		""")
		r.sourceContains("\"Int\"")
	}

	@Test fun classRefSimpleName() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val n = Int::class.simpleName
				println(n)
			}
		""")
		r.sourceContains("\"Int\"")
	}

	@Test fun classRefUserType() {
		val r = transpile("""
			package test.Main
			data class Vec2(val x: Float, val y: Float)
			fun main(args: Array<String>) {
				val n = Vec2::class.simpleName
				println(n)
			}
		""")
		r.sourceContains("\"Vec2\"")
	}

	@Test fun varianceModifiersAccepted() {
		val r = transpile("""
			package test.Main
			interface Producer<out T> {
				fun produce(): T
			}
			interface Consumer<in T> {
				fun consume(item: T)
			}
			data class Box<T>(val value: T)
			fun main(args: Array<String>) {
				val b = Box(42)
				println(b.value)
			}
		""")
		r.sourceContains("42")
	}

	@Test fun reifiedInlineTypeSubstitution() {
		val r = transpile("""
			package test.Main
			data class Point(val x: Int, val y: Int)
			inline fun <reified T> nameOf(): String = T::class.simpleName
			fun main(args: Array<String>) {
				println(nameOf<Point>())
				println(nameOf<Int>())
			}
		""")
		r.sourceContains("\"Point\"")
		r.sourceContains("\"Int\"")
	}
}
