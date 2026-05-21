package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for data classes: declaration, construction, properties,
 * toString, equals, copy.
 */
class DataClassUnitTest : TranspilerTestBase() {

    private val vec2Decl = """
        data class Vec2(val x: Float, val y: Float)
    """

    // ── Struct typedef ───────────────────────────────────────────────

    @Test fun structTypedef() {
        val r = transpileMain("val v = Vec2(1.0f, 2.0f)", decls = vec2Decl)
        r.headerContains("KTC_CLASS(")
        r.headerContains("ktc_Float x;")
        r.headerContains("ktc_Float y;")
        r.headerContains(");")
    }

    // ── Constructor (create function) ────────────────────────────────

    @Test fun createFunction() {
        val r = transpileMain("val v = Vec2(1.0f, 2.0f)", decls = vec2Decl)
        r.sourceContains("test_Main_Vec2 test_Main_Vec2_primaryConstructor(ktc_Float x, ktc_Float y)")
        r.sourceContains("test_Main_Vec2 v = test_Main_Vec2_primaryConstructor(1.0f, 2.0f);")
    }

    // ── Property access ──────────────────────────────────────────────

    @Test fun propertyAccess() {
        val r = transpileMain("val v = Vec2(1.0f, 2.0f)\nprintln(v.x)", decls = vec2Decl)
        r.sourceContains("v.x")
    }

    // ── toString ─────────────────────────────────────────────────────

    @Test fun toStringGenerated() {
        val r = transpileMain("val v = Vec2(1.0f, 2.0f)\nprintln(v)", decls = vec2Decl)
        r.sourceContains("test_Main_Vec2_toString")
        r.sourceContains("ktc_core_sb_append_str(sb, ktc_core_str(\"Vec2(x=\")")
    }

    // ── equals ───────────────────────────────────────────────────────

    @Test fun equalsGenerated() {
        val r = transpileMain(
            "val a = Vec2(1.0f, 2.0f)\nval b = Vec2(1.0f, 2.0f)\nprintln(a == b)",
            decls = vec2Decl
        )
        r.sourceContains("test_Main_Vec2_equals(a, b)")
    }

    // ── copy ─────────────────────────────────────────────────────────

    @Test fun copyNoArgs() {
        val r = transpileMain("val v = Vec2(1.0f, 2.0f)\nval v2 = v.copy()", decls = vec2Decl)
        // copy() with no args is just a struct copy
        r.sourceContains("test_Main_Vec2 v2 = v;")
    }

    @Test fun copyWithNamedArgs() {
        val r = transpileMain("val v = Vec2(1.0f, 2.0f)\nval v2 = v.copy(x = 9.0f)", decls = vec2Decl)
        r.sourceContains(".x = 9.0f;")
    }

    // ── Nested data class ────────────────────────────────────────────

    @Test fun nestedDataClass() {
        val decls = """
            data class Vec2(val x: Float, val y: Float)
            data class Rect(val origin: Vec2, val size: Vec2)
        """
        val r = transpileMain(
            "val r = Rect(Vec2(0.0f, 0.0f), Vec2(10.0f, 5.0f))\nprintln(r)",
            decls = decls
        )
        r.headerContains("test_Main_Vec2 origin;")
        r.headerContains("test_Main_Vec2 size;")
    }

    // ── new (heap constructor) ───────────────────────────────────────

    @Test fun primaryConstructor() {
        val r = transpileMain("val v = Vec2(1.0f, 2.0f)", decls = vec2Decl)
        r.sourceContains("test_Main_Vec2 test_Main_Vec2_primaryConstructor(ktc_Float x, ktc_Float y)")
    }

    // ── Nested data class (struct-type ctor arg passed by value) ─────

    @Test fun nestedDataClassCtorPassesByValue() {
        val r = transpileMain("""
            val origin = Vec2(0.0f, 0.0f)
            val size = Vec2(10.0f, 5.0f)
            val rect = Rect(origin, size)
        """, decls = """
            data class Vec2(val x: Float, val y: Float)
            data class Rect(val origin: Vec2, val size: Vec2)
        """)
        // _primaryConstructor takes Vec2 by value, not by pointer
        r.sourceContains("test_Main_Rect_primaryConstructor(test_Main_Vec2 origin, test_Main_Vec2 size)")
        // call site should NOT use &
        r.sourceContains("test_Main_Rect_primaryConstructor(origin, size)")
    }
}
