package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for type checks (`is`, `!is`) and casts (`as`, `as?`).
 * Covers class type checks, interface type checks, interface casts,
 * and non-interface C-style casts.
 */
class CastUnitTest : TranspilerTestBase() {

    @Test fun isCheckOnClass() {
        val r = transpile("""
            package test.Main
            class Shape
            class Circle(val r: Float)
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val ok = c is Circle
            }
        """)
        // Concrete class: type is statically known, generates a constant comparison
        r.sourceContains("(test_Main_Circle_TYPE_ID == test_Main_Circle_TYPE_ID)")
    }

    @Test fun isCheckNegated() {
        val r = transpile("""
            package test.Main
            class Shape
            class Circle(val r: Float)
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val ok = c !is Circle
            }
        """)
        r.sourceContains("!(test_Main_Circle_TYPE_ID == test_Main_Circle_TYPE_ID)")
    }

    @Test fun isCheckOnInterface() {
        val r = transpile("""
            package test.Main
            interface Drawable
            class Circle(val r: Float) : Drawable
            class Square(val s: Float) : Drawable
            fun main(args: Array<String>) {
                val c: Drawable = Circle(1.0f)
                val ok = c is Circle
            }
        """)
        // Interface is-check uses __typeId from the interface stack union
        r.sourceContains("KTC_GET_TYPEID(c.__typeId) == test_Main_Circle_TYPE_ID")
    }

    @Test fun isCheckOnInterfaceMultipleImpls() {
        val r = transpile("""
            package test.Main
            interface Drawable
            class Circle(val r: Float) : Drawable
            class Square(val s: Float) : Drawable
            fun main(args: Array<String>) {
                val c: Drawable = Circle(1.0f)
                val ok = c is Drawable
            }
        """)
        r.sourceContains("KTC_GET_TYPEID(c.__typeId) == test_Main_Circle_TYPE_ID || KTC_GET_TYPEID(c.__typeId) == test_Main_Square_TYPE_ID")
    }

    @Test fun asCastNonInterface() {
        val r = transpile("""
            package test.Main
            class Shape
            class Circle(val r: Float)
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val s = c as Shape
            }
        """)
        // Non-interface cast → C-style cast
        r.sourceContains("(test_Main_Shape)(c)")
    }

    @Test fun asCastToInterface() {
        val r = transpile("""
            package test.Main
            interface Drawable {
                fun draw(): Unit
            }
            class Circle(val r: Float) : Drawable {
                override fun draw() {}
            }
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val d = c as Drawable
            }
        """)
        r.sourceContains("test_Main_Circle_as_Drawable")
    }

    @Test fun safeCastAsQuestion() {
        val r = transpile("""
            package test.Main
            class Shape
            class Circle(val r: Float) : Shape
            fun main(args: Array<String>) {
                val s: Shape = Circle(1.0f)
                val c = (s as? Circle)
            }
        """)
        // Concrete class source: static comparison (Shape_TYPE_ID vs Circle_TYPE_ID)
        r.sourceContains("test_Main_Shape_TYPE_ID == test_Main_Circle_TYPE_ID")
        r.sourceContains("KTC_SOME")
        r.sourceContains("KTC_NONE")
    }

    @Test fun safeCastAsQuestionInterface() {
        val r = transpile("""
            package test.Main
            interface Drawable { fun draw(): Unit }
            class Circle(val r: Float) : Drawable {
                override fun draw() {}
            }
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val d = (c as? Drawable)
            }
        """)
        // Concrete class source: static comparison (Circle_TYPE_ID vs Circle_TYPE_ID)
        r.sourceContains("test_Main_Circle_TYPE_ID == test_Main_Circle_TYPE_ID")
    }

    @Test fun isCheckInWhen() {
        val r = transpile("""
            package test
            class Shape
            class Circle(val r: Float)
            class Square(val s: Float)
            fun main(args: Array<String>) {
                val s: Shape = Circle(1.0f)
                val res = when (s) {
                    is Circle -> 1
                    is Square -> 2
                    else -> 0
                }
            }
        """)
        // Concrete class source: static comparisons
        r.sourceContains("test_Shape_TYPE_ID == test_Circle_TYPE_ID")
        r.sourceContains("test_Shape_TYPE_ID == test_Square_TYPE_ID")
    }
}
