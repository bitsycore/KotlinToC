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
        val r = transpileMain("val p = Pair(1, 2)\nval f = p.first")
        r.sourceContains("p.first")
    }

    @Test fun pairSecondAccess() {
        val r = transpileMain("val p = Pair(1, 2)\nval s = p.second")
        r.sourceContains("p.second")
    }

    @Test fun tripleThirdAccess() {
        val r = transpileMain("val t = Triple(1, 2, 3)\nval th = t.third")
        r.sourceContains("t.third")
    }

    @Test fun pairFirstTyped() {
        val r = transpileMain("val p = Pair(\"a\", 1)\nval f = p.first")
        r.sourceContains("p.first")
    }

    @Test fun destructuringDeclarationNotYetImpl() {
        notYetImpl("val (a, b) = pair destructuring syntax is not implemented")
    }

    @Test fun componentFunctionOnDataClassNotYetImpl() {
        notYetImpl("synthetic componentN() functions on data classes are not implemented")
    }

    @Test fun tupleComponentAccess() {
        val r = transpileMainWithStdlib("""
            val t = Tuple("x", 1, true)
            val c0 = t.component0
            val c1 = t.component1
        """)
        r.sourceContains("t.component0")
        r.sourceContains("t.component1")
    }
}
