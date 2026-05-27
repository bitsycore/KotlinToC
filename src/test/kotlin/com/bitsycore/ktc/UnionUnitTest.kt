package com.bitsycore.ktc

import kotlin.test.Test

class UnionUnitTest : TranspilerTestBase() {

	@Test fun sealedInterfaceTypedef() {
		val r = transpile("""
			package test.Main
			sealed interface Shape {
				data class Circle(val radius: Float) : Shape
				data class Rect(val w: Float, val h: Float) : Shape
			}
			fun main(args: Array<String>) {
				val s: Shape = Shape.Circle(1.0f)
				println(s)
			}
		""")
		r.headerContains("Shape")
		r.headerContains("KTC_INTERFACE")
	}

	@Test fun sealedInterfaceIsSmartCast() {
		val r = transpile("""
			package test.Main
			sealed interface Shape {
				data class Circle(val radius: Float) : Shape
				data class Rect(val w: Float, val h: Float) : Shape
			}
			fun main(args: Array<String>) {
				val s: Shape = Shape.Circle(1.0f)
				if (s is Shape.Circle) {
					println(s.radius)
				}
			}
		""")
		r.sourceContains("TYPE_ID")
		r.sourceContains("radius")
	}

	@Test fun sealedInterfaceWhenSmartCast() {
		val r = transpile("""
			package test.Main
			sealed interface Shape {
				data class Circle(val radius: Float) : Shape
				data class Rect(val w: Float, val h: Float) : Shape
			}
			fun main(args: Array<String>) {
				val s: Shape = Shape.Circle(1.0f)
				when (s) {
					is Shape.Circle -> println(s.radius)
					is Shape.Rect -> println(s.w)
				}
			}
		""")
		r.sourceContains("TYPE_ID")
		r.sourceContains("radius")
		r.sourceContains(".w")
	}
}
