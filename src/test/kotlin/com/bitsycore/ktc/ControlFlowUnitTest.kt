package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for control flow: if/else, when, for, while, do-while, break, continue.
 */
class ControlFlowUnitTest : TranspilerTestBase() {

    // ── If/else statement ────────────────────────────────────────────

    @Test fun ifStatement() {
        val r = transpileMain("val x = 10\nif (x > 5) println(x)")
        r.sourceContains("if ((x > 5))")
    }

    @Test fun ifElseStatement() {
        val r = transpileMain("val x = 10\nif (x > 5) println(\"big\") else println(\"small\")")
        r.sourceContains("} else {")
    }

    // ── If expression (ternary) ──────────────────────────────────────

    @Test fun ifExpressionTernary() {
        val r = transpileMain("val a = 10\nval b = 20\nval max = if (a > b) a else b")
        r.sourceContains("(a > b) ? a : b")
    }

    @Test fun ifExpressionMultiStatement() {
        val r = transpileMain("""
            val a = 10
            val result = if (a > 5) {
                val doubled = a * 2
                doubled + 1
            } else {
                a - 1
            }
        """)
        // Multi-statement if-expr uses temp var
        r.sourceMatches(Regex("ktc_Int \\$\\d+"))
    }

    // ── When statement ───────────────────────────────────────────────

    @Test fun whenWithSubject() {
        val r = transpileMain("""
            val x = 2
            when (x) {
                1 -> println("one")
                2 -> println("two")
                else -> println("other")
            }
        """)
        r.sourceContains("if (x == 1)")
        r.sourceContains("else if (x == 2)")
        r.sourceContains("else {")
    }

    @Test fun whenWithoutSubject() {
        val r = transpileMain("""
            val x = 42
            when {
                x < 0 -> println("negative")
                x == 0 -> println("zero")
                else -> println("positive")
            }
        """)
        r.sourceContains("if ((x < 0))")
    }

    // ── When expression ──────────────────────────────────────────────

    @Test fun whenExpressionSimple() {
        val r = transpileMain("""
            val x = 2
            val name = when (x) {
                1 -> "one"
                2 -> "two"
                else -> "other"
            }
        """)
        // Simple when-expr → nested ternary
        r.sourceContains("?")
    }

    // ── For range ────────────────────────────────────────────────────

    @Test fun forRange() {
        val r = transpileMain("for (i in 0..5) { println(i) }")
        r.sourceContains("for (ktc_Int i = 0; i <= 5; i++)")
    }

    @Test fun forUntil() {
        val r = transpileMain("for (i in 0 until 5) { println(i) }")
        r.sourceContains("for (ktc_Int i = 0; i < 5; i++)")
    }

    @Test fun forRangeExclusive() {
        val r = transpileMain("for (i in 0..<5) { println(i) }")
        r.sourceContains("for (ktc_Int i = 0; i < 5; i++)")
    }

    @Test fun forDownTo() {
        val r = transpileMain("for (i in 5 downTo 0) { println(i) }")
        r.sourceContains("for (ktc_Int i = 5; i >= 0; i--)")
    }

    @Test fun forStep() {
        val r = transpileMain("for (i in 0..10 step 2) { println(i) }")
        r.sourceContains("i += 2")
    }

    @Test fun forUntilStep() {
        val r = transpileMain("for (i in 0 until 10 step 3) { println(i) }")
        r.sourceContains("i < 10")
        r.sourceContains("i += 3")
    }

    @Test fun forRangeExclusiveStep() {
        val r = transpileMain("for (i in 0..<10 step 3) { println(i) }")
        r.sourceContains("i < 10")
        r.sourceContains("i += 3")
    }

    @Test fun forDownToStep() {
        val r = transpileMain("for (i in 10 downTo 0 step 2) { println(i) }")
        r.sourceContains("i >= 0")
        r.sourceContains("i -= 2")
    }

    // ── Ranges over Char and Long ────────────────────────────────────

    @Test fun forRangeChar() {
        val r = transpileMain("for (c in 'a'..'z') { println(c) }")
        r.sourceContains("for (ktc_Char c = 'a'; c <= 'z'; c++)")
    }

    @Test fun forUntilChar() {
        val r = transpileMain("for (c in 'a' until 'd') { println(c) }")
        r.sourceContains("for (ktc_Char c = 'a'; c < 'd'; c++)")
    }

    @Test fun forDownToChar() {
        val r = transpileMain("for (c in 'z' downTo 'a') { println(c) }")
        r.sourceContains("for (ktc_Char c = 'z'; c >= 'a'; c--)")
    }

    @Test fun forRangeLong() {
        val r = transpileMain("for (i in 0L..10L) { println(i) }")
        r.sourceContains("for (ktc_Long i = 0LL; i <= 10LL; i++)")
    }

    @Test fun forUntilLong() {
        val r = transpileMain("for (i in 0L until 5L) { println(i) }")
        r.sourceContains("for (ktc_Long i = 0LL; i < 5LL; i++)")
    }

    @Test fun forDownToLong() {
        val r = transpileMain("for (i in 10L downTo 0L) { println(i) }")
        r.sourceContains("for (ktc_Long i = 10LL; i >= 0LL; i--)")
    }

    // Mixed: when either side is Long, loop variable must widen to Long.
    @Test fun forRangeMixedIntLong() {
        val r = transpileMain("val n = 10L\nfor (i in 0..n) { println(i) }")
        r.sourceContains("for (ktc_Long i = 0;")
    }

    // ── For over array ───────────────────────────────────────────────

    @Test fun forOverArray() {
        val r = transpileMain("""
            val arr = intArrayOf(1, 2, 3)
            for (x in arr) { println(x) }
        """)
        r.sourceMatches(Regex("for.*ktc_Int.*<.*arr\\.len"))
    }

    // ── While ────────────────────────────────────────────────────────

    @Test fun whileLoop() {
        val r = transpileMain("var i = 0\nwhile (i < 5) { println(i)\ni++ }")
        r.sourceContains("while ((i < 5))")
    }

    // ── Do-while ─────────────────────────────────────────────────────

    @Test fun doWhileLoop() {
        val r = transpileMain("var i = 0\ndo { println(i)\ni++ } while (i < 5)")
        r.sourceContains("do {")
        r.sourceContains("} while ((i < 5));")
    }

    // ── Break & continue ─────────────────────────────────────────────

    @Test fun breakStatement() {
        val r = transpileMain("for (i in 0..10) { if (i == 5) break\nprintln(i) }")
        r.sourceContains("break;")
    }

    @Test fun continueStatement() {
        val r = transpileMain("for (i in 0..10) { if (i == 5) continue\nprintln(i) }")
        r.sourceContains("continue;")
    }

    // ── Nested loops ─────────────────────────────────────────────────

    @Test fun nestedFor() {
        val r = transpileMain("""
            for (i in 0..2) {
                for (j in 0..2) {
                    println(i + j)
                }
            }
        """)
        r.sourceContains("for (ktc_Int i = 0; i <= 2; i++)")
        r.sourceContains("for (ktc_Int j = 0; j <= 2; j++)")
    }

    // ── If-else chains ───────────────────────────────────────────────

    @Test fun elseIfChain() {
        val r = transpileMain("""
            val x = 5
            if (x > 10) {
                println("big")
            } else if (x > 0) {
                println("small")
            } else {
                println("zero or negative")
            }
        """)
        r.sourceContains("} else")
    }
}
