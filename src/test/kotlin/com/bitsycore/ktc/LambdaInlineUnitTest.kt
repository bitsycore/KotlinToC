package com.bitsycore.ktc

import kotlin.test.Test

class LambdaInlineUnitTest : TranspilerTestBase() {

    @Test fun inlineFunBasicExpansion() {
        val r = transpile("""
            package test.Main
            inline fun twice(x: Int): Int = x * 2
            fun main(args: Array<String>) {
                val r = twice(5)
            }
        """)
        r.sourceContains("/* inline twice(x = 5): Int */")
        r.sourceContains("ktc_Int x = 5;")
        r.sourceContains("\$end_ir_")
        r.sourceNotContains("test_Main_twice")
    }

    @Test fun inlineFunWithBlockBody() {
        val r = transpile("""
            package test.Main
            inline fun greet(name: String): String {
                val msg = "Hello, " + name
                return msg
            }
            fun main(args: Array<String>) {
                val g = greet("World")
            }
        """)
        r.sourceContains("/* inline greet(name = \"World\"): String */")
        r.sourceContains("ktc_String name = ktc_core_str(\"World\");")
    }

    @Test fun inlineFunWithReturn() {
        val r = transpile("""
            package test.Main
            inline fun firstPositive(a: Int, b: Int): Int {
                if (a > 0) return a
                return b
            }
            fun main(args: Array<String>) {
                val r = firstPositive(-1, 5)
            }
        """)
        r.sourceContains("\$end_ir_")
    }

    @Test fun lambdaAsInlineArg() {
        val r = transpile("""
            package test.Main
            inline fun foo(x: Int, block: (Int) -> Int): Int {
                return block(x)
            }
            fun main(args: Array<String>) {
                val r = foo(5) { it * 2 }
            }
        """)
        r.sourceContains("/* inline foo(x = 5, block = Fun(Int)->Int): Int */")
        r.sourceContains("ktc_Int x = 5;")
    }

    @Test fun lambdaWithExplicitParams() {
        val r = transpile("""
            package test.Main
            inline fun combine(a: Int, b: Int, fn: (Int, Int) -> Int): Int {
                return fn(a, b)
            }
            fun main(args: Array<String>) {
                val r = combine(3, 7) { x, y -> x + y }
            }
        """)
        r.sourceContains("/* inline combine(a = 3, b = 7, fn = Fun(Int,Int)->Int): Int */")
        r.sourceContains("ktc_Int a = 3;")
        r.sourceContains("ktc_Int b = 7;")
    }

    @Test fun inlineFunNotEmittedAsStandalone() {
        val r = transpile("""
            package test.Main
            inline fun square(x: Int): Int = x * x
            fun main(args: Array<String>) {
                val r = square(4)
            }
        """)
        r.sourceNotContains("test_Main_square")
    }

    @Test fun lambdaStandaloneErrors() {
        notYetImpl("Standalone lambda expressions are not yet implemented")
        transpileExpectError("""
            package test.Main
            fun main(args: Array<String>) {
                val f = { x: Int -> x * 2 }
            }
        """, "Lambda can only be passed")
    }

    @Test fun stdlibLetExpansion() {
        val r = transpileMainWithStdlib("""
            val r = "hello".let { it.length }
        """)
        r.sourceContains("/* inline ")
        r.sourceContains("let(block = Fun(T)->R): R */")
        r.sourceContains("\$end_ir_")
    }

    @Test fun stdlibApplyExpansion() {
        val r = transpileMainWithStdlib("""
            val sb = StringBuilder().apply {
                append("a")
            }
        """)
        r.sourceContains("/* inline ")
        r.sourceContains("apply(block = Fun(T|)->Unit): T */")
    }

    @Test fun stdlibRunExpansion() {
        notYetImpl("run function is not yet implemented")
        val r = transpileMainWithStdlib("""
            val r = run { 42 }
        """)
        r.sourceContains("/* inline run(")
    }

    @Test fun stdlibWithExpansion() {
        notYetImpl("run function is not yet implemented")
        val r = transpileMainWithStdlib("""
            val sb = StringBuilder()
            val len = with(sb) {
                length
            }
        """)
        r.sourceContains("/* inline with(")
    }

    @Test fun stdlibAlsoExpansion() {
        val r = transpileMainWithStdlib("""
            val r = "test".also { it.length }
        """)
        r.sourceContains("/* inline ")
        r.sourceContains("also(block = Fun(T)->Unit): T */")
    }

    @Test fun stdlibTakeIfExpansion() {
        val r = transpileMainWithStdlib("""
            val r = 42.takeIf { it > 0 }
        """)
        r.sourceContains("/* inline ")
        r.sourceContains("takeIf(predicate = Fun(T)->Boolean): T */")
    }

    @Test fun stdlibRepeatExpansion() {
        val r = transpileMainWithStdlib("""
            var sum = 0
            repeat(3) { sum += it }
        """)
        r.sourceContains("/* inline repeat(")
    }
}
