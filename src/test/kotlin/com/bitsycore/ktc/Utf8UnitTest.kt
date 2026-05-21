package com.bitsycore.ktc

import kotlin.test.Test

class Utf8UnitTest : TranspilerTestBase() {

    // ── runeLen ───────────────────────────────────────────────────────

    @Test fun runeLenAscii() {
        val r = transpileMain("val s = \"Hello\"\nval n = s.runeLen\nprintln(n)")
        r.sourceContains("ktc_core_str_runeLen(s)")
    }

    @Test fun runeLenInline() {
        val r = transpileMain("val n = \"Hello\".runeLen\nprintln(n)")
        r.sourceContains("ktc_core_str_runeLen(ktc_core_str(\"Hello\"))")
    }

    // ── runeAt ────────────────────────────────────────────────────────

    @Test fun runeAt() {
        val r = transpileMain("val s = \"ABC\"\nval c = s.runeAt(1)\nprintln(c)")
        r.sourceContains("ktc_core_str_runeAt(s, 1)")
    }

    @Test fun runeAtLiteral() {
        val r = transpileMain("val c = \"ABC\".runeAt(0)\nprintln(c)")
        r.sourceContains("ktc_core_str_runeAt(ktc_core_str(\"ABC\"), 0)")
    }
}
