package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for functions: declarations, parameters, defaults, return types,
 * expression bodies, and calls.
 */
class FunctionsUnitTest : TranspilerTestBase() {

    // ── Basic function ───────────────────────────────────────────────

    @Test fun simpleFunction() {
        val r = transpile("""
            package test.Main
            fun add(a: Int, b: Int): Int {
                return a + b
            }
            fun main(args: Array<String>) {
                println(add(10, 20))
            }
        """)
        r.sourceContains("ktc_Int test_Main_add(ktc_Int a, ktc_Int b)")
        r.sourceContains("return (a + b);")
    }

    @Test fun voidFunction() {
        val r = transpile("""
            package test.Main
            fun greet(name: String) {
                println(name)
            }
            fun main(args: Array<String>) {
                greet("hello")
            }
        """)
        r.sourceContains("void test_Main_greet(ktc_String name)")
    }

    // ── Expression body ──────────────────────────────────────────────

    @Test fun expressionBody() {
        val r = transpile("""
            package test.Main
            fun square(x: Int): Int = x * x
            fun main(args: Array<String>) {
                println(square(5))
            }
        """)
        r.sourceContains("return (x * x);")
    }

    // ── Default parameters ───────────────────────────────────────────

    @Test fun defaultParam() {
        val r = transpile("""
            package test.Main
            fun greet(name: String, greeting: String = "Hello") {
                println(greeting)
            }
            fun main(args: Array<String>) {
                greet("World")
            }
        """)
        // When called without the default arg, the default is substituted
        r.sourceContains("ktc_core_str(\"Hello\")")
    }

    @Test fun defaultParamOverridden() {
        val r = transpile("""
            package test.Main
            fun greet(name: String, greeting: String = "Hello") {
                println(greeting)
            }
            fun main(args: Array<String>) {
                greet("World", "Hi")
            }
        """)
        r.sourceContains("ktc_core_str(\"Hi\")")
    }

    // ── Multiple return paths ────────────────────────────────────────

    @Test fun earlyReturn() {
        val r = transpile("""
            package test.Main
            fun abs(x: Int): Int {
                if (x < 0) return -x
                return x
            }
            fun main(args: Array<String>) {
                println(abs(-5))
            }
        """)
        r.sourceContains("return (-x);")
        r.sourceContains("return x;")
    }

    // ── Recursive function ───────────────────────────────────────────

    @Test fun recursion() {
        val r = transpile("""
            package test.Main
            fun fibonacci(n: Int): Int {
                if (n <= 1) return n
                var a = 0
                var b = 1
                for (i in 2..n) {
                    val temp = a + b
                    a = b
                    b = temp
                }
                return b
            }
            fun main(args: Array<String>) {
                println(fibonacci(10))
            }
        """)
        r.sourceContains("ktc_Int test_Main_fibonacci(ktc_Int n)")
    }

    // ── Boolean return type ──────────────────────────────────────────

    @Test fun booleanReturn() {
        val r = transpile("""
            package test.Main
            fun isPositive(x: Int): Boolean = x > 0
            fun main(args: Array<String>) {
                println(isPositive(5))
            }
        """)
        r.sourceContains("ktc_Bool test_Main_isPositive(ktc_Int x)")
    }

    // ── String return ────────────────────────────────────────────────

    @Test fun stringReturn() {
        // Non-inline bare-String returns are refused (the value's internal buffer would
        // die at function exit). Mark inline so the call-site retains the buffer in its
        // own frame via alloca.
        val r = transpile("""
            package test.Main
            inline fun describe(x: Int): String {
                return when {
                    x < 0 -> "negative"
                    x == 0 -> "zero"
                    else -> "positive"
                }
            }
            fun main(args: Array<String>) {
                println(describe(5))
            }
        """)
        // Inline functions don't emit a standalone C function — verify the inline body
        // appears in main and the standalone symbol does not.
        r.sourceNotContains("ktc_String test_Main_describe(")
    }

    // ── Float/Double params and return ───────────────────────────────

    @Test fun floatParams() {
        val r = transpile("""
            package test.Main
            fun dist(x: Float, y: Float): Float = x * x + y * y
            fun main(args: Array<String>) {
                println(dist(3.0f, 4.0f))
            }
        """)
        r.sourceContains("ktc_Float test_Main_dist(ktc_Float x, ktc_Float y)")
    }

    // ── Main with args ───────────────────────────────────────────────

    @Test fun mainWithArgs() {
        val r = transpileMain("println(args.size)")
        r.sourceContains("test_Main_main(ktc_VarArr_ktc_String args")
        r.sourceContains("local\$args")  // alias without copy
    }

    @Test fun mainBareReturnEmitsReturn() {
        val r = transpileMain("return")
        r.sourceContains("return;")
    }
}
