package com.bitsycore.ktc

import kotlin.test.Test

/*
Unit tests for method overloading with WithType1_Type2 naming.
*/
class OverloadUnitTest : TranspilerTestBase() {

    // ── Object overloads ───────────────────────────────────────────

    @Test fun objectOverloadNoArgKeepsPlainName() {
        val v = transpileMain("", """
            object O {
                fun greet(): String = "hi"
                fun greet(name: String): String = name
            }
        """)
        // No-arg overload keeps plain name, no suffix
        v.headerContains(", greet)(void)")
        // Parameterized overload gets With suffix
        v.headerContains(", greetWithString)(")
    }

    @Test fun objectOverloadWithTypeSuffix() {
        val v = transpileMain("", """
            object O {
                fun greet(): String = "hi"
                fun greet(name: String): String = name
            }
        """)
        v.headerContains(", greetWithString)(")
    }

    @Test fun objectOverloadMultiParam() {
        val v = transpileMain("", """
            object O {
                fun add(x: Int, y: Int): Int = x + y
                fun add(x: Double, y: Double): Double = x + y
            }
        """)
        v.headerContains(", addWithInt_Int)(")
        v.headerContains(", addWithDouble_Double)(")
    }

    @Test fun objectOverloadThreeVariants() {
        val v = transpileMain("", """
            object O {
                fun inc() {}
                fun inc(by: Int) {}
                fun inc(by: Int, times: Int) {}
            }
        """)
        // No-arg keeps plain name
        v.headerContains(", inc)(void)")
        v.headerContains(", incWithInt)(")
        v.headerContains(", incWithInt_Int)(")
    }

    @Test fun objectSingleMethodNoSuffix() {
        val v = transpileMain("", """
            object O {
                fun only(): Int = 42
            }
        """)
        v.headerContains(", only)(void)")
    }

    // ── Class overloads ────────────────────────────────────────────

    @Test fun classOverloadTwoVariants() {
        val v = transpileMain("", """
            class C() {
                fun log(msg: String) {}
                fun log(code: Int) {}
            }
        """)
        v.headerContains(", logWithString)(KTC_TYPE_NAME*")
        v.headerContains(", logWithInt)(KTC_TYPE_NAME*")
    }

    @Test fun classOverloadTypeMatching() {
        val v = transpileMain("val x = C().add(2.5, 3.5)", """
            class C() {
                fun add(x: Int, y: Int): Int = 0
                fun add(x: Double, y: Double): Double = 0.0
            }
        """)
        v.sourceContains("test_Main_C_addWithDouble_Double")
    }

    @Test fun classSingleMethodNoSuffix() {
        val v = transpileMain("", """
            class C() {
                fun only(): Int = 42
            }
        """)
        v.headerContains(", only)(KTC_TYPE_NAME*")
    }

    // ── Private overloads ──────────────────────────────────────────

    @Test fun privateOverloadPRIVPrefix() {
        val v = transpileMain("", """
            object O {
                private fun helper() {}
                private fun helper(x: Int) {}
            }
        """)
        v.sourceContains("O_PRIV_helper()")
        v.sourceContains("O_PRIV_helperWithInt")
    }

    // ── Top-level overloads ────────────────────────────────────────

    @Test fun topLevelOverload() {
        val v = transpile("""
            package test
            fun doIt() {}
            fun doIt(x: Int) {}
        """)
        // No-arg keeps plain name
        v.headerContains("test_doIt()")
        v.headerContains("test_doItWithInt")
    }

    // ── Bug fixes: if-assignment, equals, != ─────────────────────────

    @Test fun ifAssignmentGeneratesProperIfBlock() {
        val v = transpileMain("var ok = true; if (1 != 2) ok = false")
        // Must NOT contain broken ternary: ((cond) ? ok : 0) = false
        v.sourceContains("if (")
        v.sourceContains("ok = false;")
    }

    @Test fun regularClassHasEquals() {
        val v = transpileMain("", "class C(val x: Int)")
        v.headerContains("KTC_METHOD(ktc_Bool, equals)(KTC_TYPE_NAME a, KTC_TYPE_NAME b)")
    }

    @Test fun dataClassNotEqualsGeneratesNegatedEquals() {
        val v = transpileMain("val a = Vec2(1f,2f); val b = Vec2(2f,3f); val r = a != b", """
            data class Vec2(val x: Float, val y: Float)
        """)
        v.sourceContains("!test_Main_Vec2_equals")
    }

    @Test fun regularClassEqualsReturnsTrueForEmpty() {
        val v = transpileMain("", "class Empty()")
        v.sourceContains("test_Main_Empty_equals")
        v.sourceContains("return true;")
    }
}
