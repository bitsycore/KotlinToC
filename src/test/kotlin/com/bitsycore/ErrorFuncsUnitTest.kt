package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Unit tests for inline function dispatch (overload resolution, generic registration),
 * stdlib error functions (check, require, checkNotNull, requireNotNull), and
 * smart cast propagation after null-checking inline functions.
 */
class ErrorFuncsUnitTest : TranspilerTestBase() {

    // ── Overloaded inline resolution (user-defined, no stdlib needed) ──

    @Test fun overloadedInline1Arg() {
        val r = transpileMain("val x = oneArgInline(5)", """
            inline fun oneArgInline(a: Int): Int = a * 2
            inline fun oneArgInline(a: Int, b: Int): Int = a + b
        """)
        r.sourceContains("oneArgInline")
        r.sourceContains("a * 2")
    }

    @Test fun overloadedInline2Args() {
        val r = transpileMain("val x = twoArgInline(3, 4)", """
            inline fun twoArgInline(a: Int): Int = a * 2
            inline fun twoArgInline(a: Int, b: Int): Int = a + b
        """)
        r.sourceContains("twoArgInline")
        r.sourceContains("a + b")
    }

    // ── Inline generic registration (with <T>) ──────────────────────

    @Test fun inlineGenericRegistered() {
        val r = transpileMain("val x = genericIdentity(42)", """
            inline fun <T> genericIdentity(value: T): T = value
        """)
        r.sourceContains("42")
    }

    // ── check / require with stdlib ──────────────────────────────────

    @Test fun checkTrueExpandsInlineWithStdlib() {
        val r = transpileMainWithStdlib("check(true)")
        r.sourceContains("if (")
        r.sourceContains("Check failed")
    }

    @Test fun checkFalseWithStdlib() {
        val r = transpileMainWithStdlib("check(false)")
        r.sourceContains("ktc_std_error")
    }

    @Test fun checkWithLazyMessageWithStdlib() {
        val r = transpileMainWithStdlib("""check(1 + 1 == 2) { "fail" }""")
        r.sourceContains("fail")
    }

    @Test fun requireTrueWithStdlib() {
        val r = transpileMainWithStdlib("""require(1 > 0) { "custom" }""")
        r.sourceContains("if (")
        r.sourceContains("custom")
    }

    @Test fun requireFalseWithStdlib() {
        val r = transpileMainWithStdlib("""require(1 > 999) { "custom" }""")
        r.sourceContains("ktc_std_error")
    }

    // ── checkNotNull / requireNotNull (generic inline) with stdlib ───

    @Test fun checkNotNullExpandsWithConcreteType() {
        val r = transpileMainWithStdlib("""
            val x: Int? = 42
            val y: Int = checkNotNull(x)
        """)
        r.sourceContains("ktc_Int\$Opt value = x")
        r.sourceContains("value.value")
    }

    @Test fun checkNotNullSmartCastPropagation() {
        val r = transpileMainWithStdlib("""
            val x: Int? = 42
            val y: Int = checkNotNull(x)
            val z: Int = x
        """)
        r.sourceContains("smart-cast: 'x' narrowed")
        r.sourceContains("x.value")
    }

    @Test fun requireNotNullExpandsWithConcreteType() {
        val r = transpileMainWithStdlib("""
            val x: Float? = 3.14f
            val y: Float = requireNotNull(x)
        """)
        r.sourceContains("ktc_Float\$Opt value = x")
        r.sourceContains("value.value")
    }

    @Test fun requireNotNullSmartCastPropagation() {
        val r = transpileMainWithStdlib("""
            val x: Float? = 3.14f
            val y: Float = requireNotNull(x)
            val z: Float = x
        """)
        r.sourceContains("smart-cast: 'x' narrowed")
        r.sourceContains("x.value")
    }
}
