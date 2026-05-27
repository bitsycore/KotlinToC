package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for destructuring declarations, componentN, and related features.
 *
 * Currently the transpiler does NOT support:
 * - `val (a, b) = pair` destructuring declarations (parser limitation)
 * - Synthetic `componentN()` methods on data classes
 *
 * Pair/Triple `.first` / `.second` / `.third` work via direct struct field access.
 * Tuple `component0` / `component1` work via struct field access.
 */
class DestructuringUnitTest : TranspilerTestBase() {

    @Test fun pairFirstAccess() {
        val r = transpileMainWithStdlib("val p = Pair(1, 2)\nval f = p.first")
        r.sourceContains("p.first")
    }

    @Test fun pairSecondAccess() {
        val r = transpileMainWithStdlib("val p = Pair(1, 2)\nval s = p.second")
        r.sourceContains("p.second")
    }

    @Test fun tripleThirdAccess() {
        val r = transpileMainWithStdlib("val t = Triple(1, 2, 3)\nval th = t.third")
        r.sourceContains("t.third")
    }

    @Test fun pairFirstTyped() {
        val r = transpileMainWithStdlib("val p = Pair(\"a\", 1)\nval f = p.first")
        r.sourceContains("p.first")
    }

    @Test fun destructuringDeclPair() {
        val r = transpileMainWithStdlib("val (a, b) = Pair(10, 20)")
        r.sourceContains(".first")
        r.sourceContains(".second")
    }

    @Test fun destructuringDeclTriple() {
        val r = transpileMainWithStdlib("val (a, b, c) = Triple(1, 2, 3)")
        r.sourceContains(".first")
        r.sourceContains(".second")
        r.sourceContains(".third")
    }

    @Test fun destructuringDeclDataClass() {
        val r = transpileMain(
            "val (x, y) = Vec2(3.0f, 4.0f)",
            decls = "data class Vec2(val x: Float, val y: Float)"
        )
        // For user data classes, destructured names bind via the ctor params in order.
        r.sourceContains(".x")
        r.sourceContains(".y")
    }

    @Test fun destructuringDeclDiscardSlot() {
        val r = transpileMain(
            "val (a, _) = Vec2(7.0f, 9.0f)",
            decls = "data class Vec2(val x: Float, val y: Float)"
        )
        // The "_" slot must not emit a binding for it.
        r.sourceContains("/*VAL*/ const ktc_Float a =")
        r.sourceNotContains("ktc_Float _ =")
    }

    // Synthetic componentN() functions on data classes are not yet implemented —
    // KTC destructures by direct ctor-param field access, not via component1()/component2().
    @Test fun componentFunctionOnDataClassNotYetImpl() {
        notYetImpl("synthetic componentN() methods on data classes are not implemented; KTC uses positional field access for destructuring instead")
    }

    @Test fun tupleComponentAccess() {
        // Three-field data class with componentN-like accessors via direct field lookup.
        val r = transpileMain(
            """
                val t = Tup("x", 1, true)
                val c0 = t.first
                val c1 = t.second
            """,
            decls = "data class Tup(val first: String, val second: Int, val third: Boolean)"
        )
        r.sourceContains("t.first")
        r.sourceContains("t.second")
    }

    // ── for ((a, b) in pairs) ────────────────────────────────────────

    @Test fun forDestructuringPairList() {
        val r = transpileMainWithStdlib("""
            val list = listOf(Heap, 1 to "a", 2 to "b")
            for ((k, v) in list) { println(v) }
        """)
        // Synthetic temp $ditem_k_v binds the element; user-visible k, v
        // are extracted from it via .first / .second.
        r.sourceContains("\$ditem_k_v")
        r.sourceContains(".first")
        r.sourceContains(".second")
    }

    @Test fun forDestructuringDataClass() {
        val r = transpileMain(
            """
                val v1 = Vec2(1.0f, 2.0f)
                val arr = arrayOf<Vec2>(v1)
                for ((x, y) in arr) { println(x) }
            """,
            decls = "data class Vec2(val x: Float, val y: Float)"
        )
        r.sourceContains("\$ditem_x_y")
        // x and y are extracted from the per-iteration element. The
        // destructuring goes through a temporary tmp, so the chain is
        //   $ditem_x_y → $dN = $ditem_x_y → x = $dN.x
        // — match the field-access tail rather than the full chain.
        r.sourceMatches(Regex("const ktc_Float x = \\\$\\w+\\.x"))
        r.sourceMatches(Regex("const ktc_Float y = \\\$\\w+\\.y"))
    }

    @Test fun forDestructuringTriple() {
        val r = transpileMainWithStdlib("""
            val list = listOf(Heap, Triple(1, "a", true))
            for ((a, b, c) in list) { println(b) }
        """)
        r.sourceContains("\$ditem_a_b_c")
        r.sourceContains(".first")
        r.sourceContains(".second")
        r.sourceContains(".third")
    }

    @Test fun forDestructuringWithDiscard() {
        val r = transpileMain(
            """
                val v1 = Vec2(1.0f, 2.0f)
                val arr = arrayOf<Vec2>(v1)
                for ((x, _) in arr) { println(x) }
            """,
            decls = "data class Vec2(val x: Float, val y: Float)"
        )
        // The "_" slot does not bind a local.
        r.sourceContains("\$ditem_x__")
        r.sourceContains("const ktc_Float x =")
        r.sourceNotContains("ktc_Float _ =")
    }
}
