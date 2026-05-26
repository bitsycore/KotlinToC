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
        r.sourceContains("ktc_Int arr_data[] = {10, 20, 30};")
        // Default-on --check-bounds wraps the index in ktc_core_bounds_check(...);
        // match the prefix so the test isn't tied to the exact bounds-check form.
        r.sourceContains("arr.ptr[")
        r.sourceContains("(0)")
    }

    @Test fun intArraySize() {
        val r = transpileMain("""
            val arr = intArrayOf(10, 20, 30)
            println(arr.size)
        """)
        r.sourceContains("arr.len")
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
        r.sourceContains("test_Main_Vec2 pts_data[] =")
    }

    @Test fun arrayOfStrings() {
        val r = transpileMain("""
            val names = arrayOf("Alice", "Bob")
            println(names[0])
        """)
        r.sourceContains("ktc_String names_data[] =")
    }

    // ── For over array ───────────────────────────────────────────────

    @Test fun forOverIntArray() {
        val r = transpileMain("""
            val arr = intArrayOf(1, 2, 3)
            for (x in arr) { println(x) }
        """)
        r.sourceContains("arr.len")
    }

    @Test fun forOverStringArray() {
        val r = transpileMain("""
            val names = arrayOf("A", "B", "C")
            for (name in names) { println(name) }
        """)
        r.sourceContains("names.len")
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
        r.sourceContains("arr.len")
        r.sourceContainsXTime("ktc_core_alloca", 1)
    }

    @Test fun arrayOfNullsString() {
        val r = transpileMain("""
            val arr = arrayOfNulls<String>(3)
            println(arr.size)
        """)
        r.sourceContains("ktc_String\$Opt*")
        r.sourceContains("arr.len")
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
        r.sourceContains("arr.len")
        r.sourceContainsXTime("ktc_core_alloca")
    }

    // ── fill ─────────────────────────────────────────────────────────

    // Byte-sized element → memset with the value byte.
    @Test fun fillByteArrayUsesMemset() {
        val r = transpileMain("""
            val arr = ByteArray(8)
            arr.fill(7)
        """)
        r.sourceContains("memset((arr).ptr, (int)(7), (size_t)((arr).len));")
    }

    // Zero literal on a non-byte element → memset 0 over the byte span.
    @Test fun fillIntArrayZeroUsesMemset() {
        val r = transpileMain("""
            val arr = IntArray(4)
            arr.fill(0)
        """)
        r.sourceContains("memset((arr).ptr, 0, sizeof(ktc_Int) * (size_t)((arr).len));")
    }

    // Non-zero, non-byte element → bounded element loop.
    @Test fun fillIntArrayNonZeroUsesLoop() {
        val r = transpileMain("""
            val arr = IntArray(4)
            arr.fill(9)
        """)
        r.sourceContains("(arr).ptr;")
        r.sourceMatches(Regex("""for \(size_t \$\d+ = 0; \$\d+ < \$\d+; \$\d+\+\+\) \$\d+\[\$\d+\] = \$\d+;"""))
    }

    // fill(value, fromIndex, toIndex) → ranged memset over a byte-sized element.
    @Test fun fillByteArrayRangeUsesMemset() {
        val r = transpileMain("""
            val arr = ByteArray(8)
            arr.fill(7, 2, 5)
        """)
        r.sourceContains("memset(((arr).ptr) + (2), (int)(7), (size_t)((5) - (2)));")
    }

    // fill(value, fromIndex) → toIndex defaults to size.
    @Test fun fillIntArrayFromIndexDefaultsToSize() {
        val r = transpileMain("""
            val arr = IntArray(6)
            arr.fill(0, 3)
        """)
        r.sourceContains("memset(((arr).ptr) + (3), 0, sizeof(ktc_Int) * (size_t)(((arr).len) - (3)));")
    }

    // RawArray ranged fill: fill(size, value, fromIndex, toIndex).
    @Test fun fillRawArrayRange() {
        val r = transpileMain("""
            val src = ByteArray(8)
            val raw: RawArray<Byte> = src.asRef()
            raw.fill(8, 0, 1, 4)
        """)
        r.sourceContains("memset((raw) + (1), 0, sizeof(ktc_Byte) * (size_t)((4) - (1)));")
    }

    // RawArray has no length → count passed explicitly.
    @Test fun fillRawArrayWithCount() {
        val r = transpileMain("""
            val src = ByteArray(8)
            val raw: RawArray<Byte> = src.asRef()
            raw.fill(8, 0)
        """)
        r.sourceContains("memset(raw, 0, sizeof(ktc_Byte) * (size_t)(8));")
    }

    // ── asRaw / asArray ──────────────────────────────────────────────

    @Test fun asRawExtractsBarePointer() {
        val r = transpileMain("""
            val arr = IntArray(4)
            val raw: RawArray<Int> = arr.asRaw()
        """)
        r.sourceContains("ktc_Int* raw")
        r.sourceContains("(arr).ptr;")
    }

    @Test fun asArrayBuildsVarArrOverSameData() {
        val r = transpileMain("""
            val src = IntArray(4)
            val raw: RawArray<Int> = src.asRaw()
            val view: Ref<Array<Int>> = raw.asArray(4)
            println(view.size)
        """)
        r.sourceContains("(ktc_VarArr_ktc_Int){raw, 4}")
        r.sourceContains("view.len")
    }

    // resizeWith on a RawArray reallocs the bare pointer (no VarArr) with the right element size.
    @Test fun resizeWithRawArrayReallocsBarePointer() {
        val r = transpileMainWithStdlib("""
            var raw: RawArray<Int> = RawArray<Int>(4).allocWith(Heap)!!
            raw = raw.resizeWith(Heap, 8)
        """)
        r.sourceContains("reallocMem")
        r.sourceContains("sizeof(ktc_Int) * (size_t)(8)")
        // raw resize returns the bare pointer — no VarArr struct is built
        r.sourceNotContains("ktc_VarArr_ktc_Int")
    }

}
