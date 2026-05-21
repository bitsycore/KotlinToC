package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for basic types, literals, and variable declarations.
 */
class BasicTypesUnitTest : TranspilerTestBase() {

    // ── Int ──────────────────────────────────────────────────────────

    @Test fun intLiteral() {
        val r = transpileMain("val x: Int = 42\nprintln(x)")
        r.sourceContains("ktc_Int x = 42;")
    }

    @Test fun intArithmetic() {
        val r = transpileMain("val a = 10\nval b = 20\nval c = a + b\nprintln(c)")
        r.sourceContains("ktc_Int c = (a + b);")
    }

    @Test fun intNegative() {
        val r = transpileMain("val x = -5\nprintln(x)")
        r.sourceContains("ktc_Int x = (-5);")
    }

    // ── Long ─────────────────────────────────────────────────────────

    @Test fun longLiteral() {
        val r = transpileMain("val x: Long = 100L\nprintln(x)")
        r.sourceContains("ktc_Long x = 100LL;")
    }

    // ── Float ────────────────────────────────────────────────────────

    @Test fun floatLiteral() {
        val r = transpileMain("val x = 3.14f\nprintln(x)")
        r.sourceContains("ktc_Float x = 3.14f;")
    }

    // ── Double ───────────────────────────────────────────────────────

    @Test fun doubleLiteral() {
        val r = transpileMain("val x = 3.14\nprintln(x)")
        r.sourceContains("ktc_Double x = 3.14;")
    }

    // ── Boolean ──────────────────────────────────────────────────────

    @Test fun boolTrue() {
        val r = transpileMain("val x = true\nprintln(x)")
        r.sourceContains("ktc_Bool x = true;")
    }

    @Test fun boolFalse() {
        val r = transpileMain("val x = false\nprintln(x)")
        r.sourceContains("ktc_Bool x = false;")
    }

    // ── Char ─────────────────────────────────────────────────────────

    @Test fun charLiteral() {
        val r = transpileMain("val c = 'A'\nprintln(c)")
        r.sourceContains("ktc_Char c = 'A';")
    }

    // ── String ───────────────────────────────────────────────────────

    @Test fun stringLiteral() {
        val r = transpileMain("val s = \"hello\"\nprintln(s)")
        r.sourceContains("ktc_String s = ktc_core_str(\"hello\")")
    }

    @Test fun stringLength() {
        val r = transpileMain("val s = \"hello\"\nprintln(s.length)")
        r.sourceContains(".len")
    }

    // ── Val vs Var ───────────────────────────────────────────────────

    @Test fun valDeclaration() {
        val r = transpileMain("val x = 10")
        r.sourceContains("ktc_Int x = 10;")
    }

    @Test fun varDeclaration() {
        val r = transpileMain("var x = 10\nx = 20")
        r.sourceContains("ktc_Int x = 10;")
        r.sourceContains("x = 20;")
    }

    @Test fun varReassignment() {
        val r = transpileMain("var x = 10\nx += 5")
        r.sourceContains("x += 5;")
    }

    @Test fun valReassignError() {
        transpileMainExpectError("val x = 10\nx = 20", "Val cannot be reassigned: 'x'")
    }

    @Test fun valCompoundAssignError() {
        transpileMainExpectError("val x = 10\nx += 5", "Val cannot be reassigned: 'x'")
    }

    @Test fun valReassignClassFieldError() {
        val decls = "data class Vec2(val x: Float, val y: Float)"
        transpileMainExpectError("val v = Vec2(1.0f, 2.0f)\nv.x = 99.0f", "Val cannot be reassigned: 'x'", decls = decls)
    }

    @Test fun valReassignClassFieldViaThisError() {
        val decls = """
            class Foo(val x: Int) {
                fun mutate() { x = 42 }
            }
        """.trimIndent()
        transpileExpectError("package test.Main\n$decls\nfun main() {}", "Val cannot be reassigned: 'x'")
    }

    @Test fun varReassignClassFieldOk() {
        val decls = "data class Vec2(var x: Float, var y: Float)"
        val r = transpileMain("val v = Vec2(1.0f, 2.0f)\nv.x = 99.0f", decls = decls)
        r.sourceContains("v.x = 99.0f;")
    }

    // ── private set ──────────────────────────────────────────────────

    @Test fun privateSetInternalWrite() {
        val decls = """
            class Player(val name: String) {
                var health: Int = 100
                    private set
                fun takeDamage(amount: Int) { health -= amount }
            }
        """.trimIndent()
        val r = transpileMain("val p = Player(\"Alice\")\np.takeDamage(10)", decls = decls)
        r.sourceContains("health -= amount;")
    }

    @Test fun privateSetExternalWriteError() {
        val decls = """
            class Player(val name: String) {
                var health: Int = 100
                    private set
            }
        """.trimIndent()
        transpileMainExpectError("val p = Player(\"Alice\")\np.health = 50", "Var with private set cannot be reassigned outside its class: 'health'", decls = decls)
    }

    @Test fun privateSetOnValError() {
        val src = """
            package test.Main
            class Foo {
                val x: Int = 0
                    private set
            }
            fun main() {}
        """.trimIndent()
        transpileExpectError(src, "'private set' is not allowed on 'val'")
    }

    // ── Type conversions ─────────────────────────────────────────────

    @Test fun toFloat() {
        val r = transpileMain("val x = 42\nval f = x.toFloat()")
        r.sourceContains("(ktc_Float)(x)")
    }

    @Test fun toLong() {
        val r = transpileMain("val x = 42\nval l = x.toLong()")
        r.sourceContains("(ktc_Long)(x)")
    }

    @Test fun toInt() {
        val r = transpileMain("val f = 3.14\nval i = f.toInt()")
        r.sourceContains("(ktc_Int)(f)")
    }

    @Test fun toDouble() {
        val r = transpileMain("val x = 42\nval d = x.toDouble()")
        r.sourceContains("(ktc_Double)(x)")
    }

    @Test fun toByte() {
        val r = transpileMain("val x = 65\nval b = x.toByte()")
        r.sourceContains("(ktc_Byte)(x)")
    }

    @Test fun toChar() {
        val r = transpileMain("val x = 65\nval c = x.toChar()")
        r.sourceContains("(ktc_Char)(x)")
    }

    // ── println for each type ────────────────────────────────────────

    @Test fun printlnInt() {
        val r = transpileMain("println(42)")
        r.sourceContains("PRId32")
    }

    @Test fun printlnString() {
        val r = transpileMain("""println("hello")""")
        r.sourceContains("%.*s")
    }

    @Test fun printlnBool() {
        val r = transpileMain("println(true)")
        r.sourceContains("\"true\"")
    }

    @Test fun printlnFloat() {
        val r = transpileMain("println(3.14f)")
        r.sourceContains("%f")
    }

    @Test fun printlnEmpty() {
        val r = transpileMain("println()")
        r.sourceContains("printf(\"\\n\")")
    }
}
