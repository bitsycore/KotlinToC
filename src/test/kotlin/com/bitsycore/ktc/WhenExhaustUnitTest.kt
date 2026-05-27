package com.bitsycore.ktc

import kotlin.test.Test

class WhenExhaustUnitTest : TranspilerTestBase() {

	@Test fun exhaustiveEnumWhenNoWarning() {
		val r = transpile("""
			package test.Main
			enum class Dir { UP, DOWN, LEFT, RIGHT }
			fun main(args: Array<String>) {
				val d = Dir.UP
				when (d) {
					Dir.UP -> println("up")
					Dir.DOWN -> println("down")
					Dir.LEFT -> println("left")
					Dir.RIGHT -> println("right")
				}
			}
		""")
		r.hasNoWarnings()
	}

	@Test fun nonExhaustiveEnumWhenWarns() {
		val r = transpile("""
			package test.Main
			enum class Dir { UP, DOWN, LEFT, RIGHT }
			fun main(args: Array<String>) {
				val d = Dir.UP
				when (d) {
					Dir.UP -> println("up")
					Dir.DOWN -> println("down")
				}
			}
		""")
		r.hasWarnings(1)
	}

	@Test fun enumWhenWithElseNoWarning() {
		val r = transpile("""
			package test.Main
			enum class Dir { UP, DOWN, LEFT, RIGHT }
			fun main(args: Array<String>) {
				val d = Dir.UP
				when (d) {
					Dir.UP -> println("up")
					else -> println("other")
				}
			}
		""")
		r.hasNoWarnings()
	}

	@Test fun nonExhaustiveEnumWhenExpr() {
		val r = transpile("""
			package test.Main
			enum class Color { RED, GREEN, BLUE }
			fun main(args: Array<String>) {
				val c = Color.RED
				val s = when (c) {
					Color.RED -> "r"
					Color.GREEN -> "g"
					else -> "?"
				}
				println(s)
			}
		""")
		r.hasNoWarnings()
	}

	@Test fun nonEnumWhenNoWarning() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 5
				when (x) {
					1 -> println("one")
					2 -> println("two")
				}
			}
		""")
		r.hasNoWarnings()
	}

	@Test fun subjectlessWhenNoWarning() {
		val r = transpile("""
			package test.Main
			fun main(args: Array<String>) {
				val x = 5
				when {
					x > 0 -> println("positive")
					x < 0 -> println("negative")
				}
			}
		""")
		r.hasNoWarnings()
	}

	// ── sealed-class / sealed-interface exhaustiveness ──────────────

	@Test fun exhaustiveSealedInterfaceNoWarning() {
		val r = transpile("""
			package test.Main
			sealed interface Shape
			class Circle(val r: Float) : Shape
			class Square(val side: Float) : Shape
			fun area(s: Shape): Float = when (s) {
				is Circle -> s.r
				is Square -> s.side
			}
			fun main(args: Array<String>) { println(area(Circle(1.0f))) }
		""")
		r.hasNoWarnings()
	}

	@Test fun nonExhaustiveSealedInterfaceWarns() {
		val r = transpile("""
			package test.Main
			sealed interface Shape
			class Circle(val r: Float) : Shape
			class Square(val side: Float) : Shape
			class Triangle(val base: Float) : Shape
			fun area(s: Shape): Float = when (s) {
				is Circle -> s.r
				is Square -> s.side
			}
			fun main(args: Array<String>) { println(area(Circle(1.0f))) }
		""")
		r.hasWarnings(1)
	}

	@Test fun exhaustiveSealedClassNoWarning() {
		val r = transpile("""
			package test.Main
			sealed class Animal
			class Cat : Animal()
			class Dog : Animal()
			fun greet(a: Animal): Int = when (a) {
				is Cat -> 1
				is Dog -> 2
			}
			fun main(args: Array<String>) { println(greet(Cat())) }
		""")
		r.hasNoWarnings()
	}

	@Test fun nonExhaustiveSealedClassWarns() {
		val r = transpile("""
			package test.Main
			sealed class Animal
			class Cat : Animal()
			class Dog : Animal()
			class Fish : Animal()
			fun greet(a: Animal): Int = when (a) {
				is Cat -> 1
				is Dog -> 2
			}
			fun main(args: Array<String>) { println(greet(Cat())) }
		""")
		r.hasWarnings(1)
	}

	@Test fun sealedWhenWithElseNoWarning() {
		val r = transpile("""
			package test.Main
			sealed interface Op
			class Add : Op
			class Sub : Op
			class Mul : Op
			fun apply(o: Op): Int = when (o) {
				is Add -> 1
				else -> 0
			}
			fun main(args: Array<String>) { println(apply(Add())) }
		""")
		r.hasNoWarnings()
	}
}
