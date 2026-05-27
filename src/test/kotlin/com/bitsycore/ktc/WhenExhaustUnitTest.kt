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
}
