package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for function pointers: ::ref, function pointer types, passing as arguments.
 */
class FunctionPointerUnitTest : TranspilerTestBase() {

    private val funPtrDecls = """
        fun addTwo(a: Int, b: Int): Int {
            return a + b
        }
        fun mulTwo(a: Int, b: Int): Int {
            return a * b
        }
        fun applyOp(x: Int, y: Int, op: (Int, Int) -> Int): Int {
            return op(x, y)
        }
    """

    // ── Function reference ───────────────────────────────────────────

    @Test fun functionReference() {
        val r = transpile("""
            package test.Main
            $funPtrDecls
            fun main(args: Array<String>) {
                val f: (Int, Int) -> Int = ::addTwo
                println(f(3, 4))
            }
        """)
        r.sourceContains("test_Main_addTwo")
    }

    // ── Pass function reference as argument ──────────────────────────

    @Test fun passFunctionRef() {
        val r = transpile("""
            package test.Main
            $funPtrDecls
            fun main(args: Array<String>) {
                println(applyOp(5, 6, ::addTwo))
                println(applyOp(5, 6, ::mulTwo))
            }
        """)
        r.sourceContains("test_Main_applyOp(5, 6, test_Main_addTwo)")
        r.sourceContains("test_Main_applyOp(5, 6, test_Main_mulTwo)")
    }

    // ── Function pointer type in signature ───────────────────────────

    @Test fun functionPointerParam() {
        val r = transpile("""
            package test.Main
            $funPtrDecls
            fun main(args: Array<String>) {
                println(applyOp(5, 6, ::addTwo))
            }
        """)
        // Function pointer param: ktc_Int (*op)(ktc_Int, ktc_Int)
        r.sourceMatches(Regex("ktc_Int \\(\\*op\\)\\(ktc_Int, ktc_Int\\)"))
    }

    // ── Reassign function pointer ────────────────────────────────────

    @Test fun reassignFunPtr() {
        val r = transpile("""
            package test.Main
            $funPtrDecls
            fun main(args: Array<String>) {
                var g: (Int, Int) -> Int = ::addTwo
                println(g(10, 20))
                g = ::mulTwo
                println(g(10, 20))
            }
        """)
        r.sourceContains("g = test_Main_mulTwo;")
    }

    // ── Call via function pointer variable ────────────────────────────

    @Test fun callViaFunPtr() {
        val r = transpile("""
            package test.Main
            $funPtrDecls
            fun main(args: Array<String>) {
                val f: (Int, Int) -> Int = ::addTwo
                println(f(3, 4))
            }
        """)
        r.sourceContains("f(3, 4)")
    }
}
