package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for C interop via the `c.` package prefix.
 * C standard library functions and constants are accessed as c.printf(), c.EXIT_SUCCESS, etc.
 */
class CInteropUnitTest : TranspilerTestBase() {

    // ── C function calls ─────────────────────────────────────────────

    @Test fun cPrintf() {
        val r = transpileMain("""c.printf(42)""")
        r.sourceContains("printf(42)")
    }

    @Test fun cPrintfWithString() {
        val r = transpileMain("""c.printf("hello %d", 42)""")
        // string literals in c.* calls are unwrapped to raw C strings (not ktc_core_str)
        r.sourceContains("printf(\"hello %d\", 42)")
    }

    @Test fun cMemset() {
        val r = transpileMain("""
            val buf = IntArray(10)
            c.memset(buf, 0, 40)
        """)
        r.sourceContains("memset(buf, 0, 40)")
    }

    @Test fun cSqrt() {
        val r = transpileMain("""val x = c.sqrt(2.0)""")
        r.sourceContains("sqrt(2.0)")
    }

    @Test fun cAbs() {
        val r = transpileMain("""val x = c.abs(-5)""")
        r.sourceContains("abs((-5))")
    }

    // ── C constants / macros ─────────────────────────────────────────

    @Test fun cConstant() {
        val r = transpileMain("""val code: Int = c.EXIT_SUCCESS""")
        r.sourceContains("EXIT_SUCCESS")
        r.sourceNotContains("c.EXIT_SUCCESS")
    }

    @Test fun cNull() {
        val r = transpileMain("""val p = c.NULL""")
        r.sourceContains("NULL")
        r.sourceNotContains("c.NULL")
    }

    // ── No prefix on output ──────────────────────────────────────────

    @Test fun noPrefixOnCalls() {
        val r = transpileMain("""c.puts(42)""")
        r.sourceContains("puts(42)")
        r.sourceNotContains("test_Main_puts")
    }

    @Test fun cExit() {
        val r = transpileMain("""c.exit(1)""")
        r.sourceContains("exit(1)")
    }

    @Test fun cFopen() {
        val r = transpileMain("""val f = c.fopen(1, 2)""")
        r.sourceContains("fopen(1, 2)")
    }

    // ── Multiple C calls ─────────────────────────────────────────────

    @Test fun multipleCCalls() {
        val r = transpileMain("""
            c.srand(42)
            val x = c.rand()
            c.printf(x)
        """)
        r.sourceContains("srand(42)")
        r.sourceContains("rand()")
        r.sourceContains("printf(x)")
    }

    // ── C function in expressions ────────────────────────────────────

    @Test fun cCallInExpression() {
        val r = transpileMain("""
            val a = c.abs(-10) + c.abs(-20)
        """)
        r.sourceContains("abs((-10))")
        r.sourceContains("abs((-20))")
    }

    @Test fun cCallAsArgument() {
        val r = transpileMain("""
            val x = c.abs(c.abs(-5))
        """)
        r.sourceContains("abs(abs((-5)))")
    }
}
