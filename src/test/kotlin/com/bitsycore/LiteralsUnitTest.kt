package com.bitsycore.ktc

import kotlin.test.Test

class LiteralsUnitTest : TranspilerTestBase() {

    // ── Decimal Int ───────────────────────────────────────────────────

    @Test fun decimalInt() {
        val r = transpile("package test.Main\nfun main() { val x = 42 }")
        r.sourceContains("ktc_Int x = 42;")
    }

    @Test fun decimalNegative() {
        val r = transpile("package test.Main\nfun main() { val x = -5 }")
        r.sourceContains("ktc_Int x = (-5);")
    }

    // ── Long ──────────────────────────────────────────────────────────

    @Test fun longLiteral() {
        val r = transpile("package test.Main\nfun main() { val x = 100L }")
        r.sourceContains("ktc_Long x = 100LL;")
    }

    // ── Unsigned Int ──────────────────────────────────────────────────

    @Test fun unsignedIntUpper() {
        val r = transpile("package test.Main\nfun main() { val x = 42U }")
        r.sourceContains("ktc_UInt x = 42U;")
    }

    @Test fun unsignedIntLower() {
        val r = transpile("package test.Main\nfun main() { val x = 42u }")
        r.sourceContains("ktc_UInt x = 42U;")
    }

    // ── Unsigned Long ─────────────────────────────────────────────────

    @Test fun unsignedLongUL() {
        val r = transpile("package test.Main\nfun main() { val x = 42UL }")
        r.sourceContains("ktc_ULong x = 42ULL;")
    }

    @Test fun unsignedLongULower() {
        val r = transpile("package test.Main\nfun main() { val x = 42uL }")
        r.sourceContains("ktc_ULong x = 42ULL;")
    }

    // ── Hexadecimal Int ───────────────────────────────────────────────

    @Test fun hexInt() {
        val r = transpile("package test.Main\nfun main() { val x = 0xFF }")
        r.sourceContains("ktc_Int x = 0xff;")
    }

    @Test fun hexIntUnderscore() {
        val r = transpile("package test.Main\nfun main() { val x = 0xFF_FF }")
        r.sourceContains("ktc_Int x = 0xffff;")
    }

    // ── Hexadecimal Unsigned ──────────────────────────────────────────

    @Test fun hexUInt() {
        val r = transpile("package test.Main\nfun main() { val x = 0xFFu }")
        r.sourceContains("ktc_UInt x = 0xffU;")
    }

    @Test fun hexULongLower() {
        val r = transpile("package test.Main\nfun main() { val x = 0xFFuL }")
        r.sourceContains("ktc_ULong x = 0xffULL;")
    }

    @Test fun hexULongUpper() {
        val r = transpile("package test.Main\nfun main() { val x = 0xFFUL }")
        r.sourceContains("ktc_ULong x = 0xffULL;")
    }

    // ── Binary ────────────────────────────────────────────────────────

    @Test fun binaryInt() {
        val r = transpile("package test.Main\nfun main() { val x = 0b1010 }")
        r.sourceContains("ktc_Int x = 10;")
    }

    @Test fun binaryIntUnderscore() {
        val r = transpile("package test.Main\nfun main() { val x = 0b1111_0000 }")
        r.sourceContains("ktc_Int x = 240;")
    }

    @Test fun binaryUInt() {
        val r = transpile("package test.Main\nfun main() { val x = 0b1010u }")
        r.sourceContains("ktc_UInt x = 10U;")
    }

    @Test fun binaryULong() {
        val r = transpile("package test.Main\nfun main() { val x = 0b1010uL }")
        r.sourceContains("ktc_ULong x = 10ULL;")
    }

    // ── Underscores in decimals ───────────────────────────────────────

    @Test fun underscoredDec() {
        val r = transpile("package test.Main\nfun main() { val x = 1_000_000 }")
        r.sourceContains("ktc_Int x = 1000000;")
    }

    @Test fun underscoredLong() {
        val r = transpile("package test.Main\nfun main() { val x = 1_000L }")
        r.sourceContains("ktc_Long x = 1000LL;")
    }

    @Test fun underscoredUInt() {
        val r = transpile("package test.Main\nfun main() { val x = 1_000u }")
        r.sourceContains("ktc_UInt x = 1000U;")
    }

    @Test fun underscoredULong() {
        val r = transpile("package test.Main\nfun main() { val x = 1_000uL }")
        r.sourceContains("ktc_ULong x = 1000ULL;")
    }

    // ── Float ─────────────────────────────────────────────────────────

    @Test fun floatUpper() {
        val r = transpileMain("val x = 3.14F\nprintln(x)")
        r.sourceContains("ktc_Float x = 3.14f;")
    }

    @Test fun floatLower() {
        val r = transpileMain("val x = 3.14f\nprintln(x)")
        r.sourceContains("ktc_Float x = 3.14f;")
    }

    @Test fun floatScientific() {
        val r = transpile("package test.Main\nfun main() { val x = 1.2e3f }")
        r.sourceContains("ktc_Float x = 1200.0f;")
    }

    // ── Double ────────────────────────────────────────────────────────

    @Test fun doublePlain() {
        val r = transpile("package test.Main\nfun main() { val x = 3.14 }")
        r.sourceContains("ktc_Double x = 3.14;")
    }

    @Test fun doubleZeroDec() {
        val r = transpile("package test.Main\nfun main() { val x = 3.0 }")
        r.sourceContains("ktc_Double x = 3.0;")
    }

    @Test fun doubleScientific() {
        val r = transpile("package test.Main\nfun main() { val x = 1.2e3 }")
        r.sourceContains("ktc_Double x = 1200.0;")
    }

    @Test fun doubleScientificNeg() {
        val r = transpile("package test.Main\nfun main() { val x = 1.2E-3 }")
        r.sourceContains("ktc_Double x = 0.0012;")
    }

    @Test fun underscoredDouble() {
        val r = transpile("package test.Main\nfun main() { val x = 1_234.567_890 }")
        r.sourceContains("ktc_Double x = 1234.56789;")
    }

    @Test fun underscoredFloat() {
        val r = transpile("package test.Main\nfun main() { val x = 1_234.567f }")
        r.sourceContains("ktc_Float x = 1234.567f;")
    }

    // ── Boolean ───────────────────────────────────────────────────────

    @Test fun boolTrue() {
        val r = transpileMain("val x = true\nprintln(x)")
        r.sourceContains("ktc_Bool x = true;")
    }

    @Test fun boolFalse() {
        val r = transpileMain("val x = false\nprintln(x)")
        r.sourceContains("ktc_Bool x = false;")
    }

    // ── Char ──────────────────────────────────────────────────────────

    @Test fun charSimple() {
        val r = transpileMain("val x = 'A'\nprintln(x)")
        r.sourceContains("ktc_Char x = 'A';")
    }

    @Test fun charDigit() {
        val r = transpileMain("val x = '7'\nprintln(x)")
        r.sourceContains("ktc_Char x = '7';")
    }

    @Test fun charEscapeNewline() {
        val r = transpileMain("val x = '\\n'\nprintln(x)")
        r.sourceContains("ktc_Char x = '\\n';")
    }

    @Test fun charEscapeTab() {
        val r = transpileMain("val x = '\\t'\nprintln(x)")
        r.sourceContains("ktc_Char x = '\\t';")
    }

    @Test fun charEscapeBackslash() {
        val r = transpileMain("val x = '\\\\'\nprintln(x)")
        r.sourceContains("ktc_Char x = '\\\\';")
    }

    @Test fun charEscapeQuote() {
        val r = transpileMain("val x = '\\''\nprintln(x)")
        r.sourceContains("ktc_Char x = '\\'';")
    }

    @Test fun charUnicode() {
        val r = transpileMain("val x = '\\u0041'\nprintln(x)")
        r.sourceContains("ktc_Char x = 'A';")
    }

    // ── String ────────────────────────────────────────────────────────

    @Test fun stringSimple() {
        val r = transpileMain("val x = \"Hello\"\nprintln(x)")
        r.sourceContains("ktc_core_str(\"Hello\")")
    }

    @Test fun stringEmpty() {
        val r = transpileMain("val x = \"\"\nprintln(x)")
        r.sourceContains("ktc_core_str(\"\")")
    }

    @Test fun stringEscape() {
        val r = transpileMain("val x = \"Line1\\nLine2\"\nprintln(x)")
        r.sourceContains("Line1\\nLine2")
    }

    // ── Null ──────────────────────────────────────────────────────────

    @Test fun nullLiteral() {
        val r = transpile("""
            package test.Main
            fun main(args: Array<String>) {
                val x: Int? = null
            }
        """.trimIndent())
        r.sourceContains("KTC_NONE")
    }
}
