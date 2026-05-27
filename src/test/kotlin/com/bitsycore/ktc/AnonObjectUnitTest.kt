package com.bitsycore.ktc

import kotlin.test.Test

class AnonObjectUnitTest : TranspilerTestBase() {

	@Test fun anonObjectImplementsInterface() {
		val r = transpile("""
			package test.Main
			interface Greeter {
				fun greet(): Int
			}
			fun useGreeter(g: Greeter): Int = g.greet()
			fun main(args: Array<String>) {
				val g: Greeter = object : Greeter {
					override fun greet(): Int = 42
				}
				println(useGreeter(g))
			}
		""")
		r.sourceContains("anon_0")
		r.sourceContains("_vt")
	}

	@Test fun anonObjectMultipleMethods() {
		val r = transpile("""
			package test.Main
			interface Shape {
				fun area(): Float
				fun name(): Int
			}
			fun main(args: Array<String>) {
				val s: Shape = object : Shape {
					override fun area(): Float = 3.14f
					override fun name(): Int = 1
				}
				println(s.area())
			}
		""")
		r.sourceContains("anon_0")
	}
}
