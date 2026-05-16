package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for arrays (arrayOf, intArrayOf).
 */
class ArrayUnitTest : TranspilerTestBase() {

    // ── intArrayOf ───────────────────────────────────────────────────

    @Test fun intArrayOf() {
        val r = transpileMain("""
            val arr = intArrayOf(10, 20, 30)
            println(arr[0])
        """)
        r.sourceContains("ktc_Int arr[] = {10, 20, 30};")
        r.sourceContains("arr[0]")
    }

    @Test fun intArraySize() {
        val r = transpileMain("""
            val arr = intArrayOf(10, 20, 30)
            println(arr.size)
        """)
        r.sourceContains("arr\$len")
    }

    // ── arrayOf with data class ──────────────────────────────────────

    @Test fun arrayOfDataClass() {
        val r = transpile("""
            package test.Main
            data class Vec2(val x: Float, val y: Float)
            fun main(args: Array<String>) {
                val pts = arrayOf(Vec2(1.0f, 2.0f), Vec2(3.0f, 4.0f))
                println(pts.size)
            }
        """)
        r.sourceContains("test_Main_Vec2 pts[] =")
    }

    @Test fun arrayOfStrings() {
        val r = transpileMain("""
            val names = arrayOf("Alice", "Bob")
            println(names[0])
        """)
        r.sourceContains("ktc_String names[] =")
    }

    // ── For over array ───────────────────────────────────────────────

    @Test fun forOverIntArray() {
        val r = transpileMain("""
            val arr = intArrayOf(1, 2, 3)
            for (x in arr) { println(x) }
        """)
        r.sourceContains("arr\$len")
    }

    @Test fun forOverStringArray() {
        val r = transpileMain("""
            val names = arrayOf("A", "B", "C")
            for (name in names) { println(name) }
        """)
        r.sourceContains("names\$len")
    }

    // ── Array index write ────────────────────────────────────────────

    // ── arrayOfNulls ──────────────────────────────────────────────────

    @Test fun arrayOfNullsInt() {
        val r = transpileMain("""
            val arr = arrayOfNulls<Int>(5)
            println(arr.size)
        """)
        r.sourceContains("ktc_Int\$Opt*")
        r.sourceContains("ktc_core_alloca")
        r.sourceContains("arr\$len")
        r.sourceContainsXTime("ktc_core_alloca", 1)
    }

    @Test fun arrayOfNullsString() {
        val r = transpileMain("""
            val arr = arrayOfNulls<String>(3)
            println(arr.size)
        """)
        r.sourceContains("ktc_String\$Opt*")
        r.sourceContains("arr\$len")
        r.sourceContainsXTime("ktc_core_alloca", 1)
    }

    @Test fun arrayOfNullsAccess() {
        val r = transpileMain("""
            val arr = arrayOfNulls<Int>(3)
            val v: Int? = arr[0]
        """)
        r.sourceContains("ktc_Int\$Opt")
        r.sourceContains("arr")
        r.sourceContainsXTime("ktc_core_alloca", 1)
    }

    @Test fun arrayOfNullsWithVariableSize() {
        val r = transpileMain("""
            val n = 10
            val arr = arrayOfNulls<Float>(n)
            println(arr.size)
        """)
        r.sourceContains("ktc_Float\$Opt*")
        r.sourceContains("arr\$len")
        r.sourceContainsXTime("ktc_core_alloca")
    }

}
