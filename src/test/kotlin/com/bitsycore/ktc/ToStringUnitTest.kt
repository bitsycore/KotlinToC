package com.bitsycore.ktc

import kotlin.test.Test
import kotlin.test.assertFalse

class ToStringUnitTest : TranspilerTestBase() {

    @Test fun `class default toString includes name and hex hash`() {
        val r = transpileMain(
            decls = "class Foo(val x: Int)",
            body = "val f = Foo(42)\nprintln(f)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("Foo_hashCode")
        r.sourceContains("%s@%x")
    }

    @Test fun `data class uses generated toString not default`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int, val y: Int)",
            body = "val p = Point(1, 2)\nprintln(p)"
        )
        r.sourceContains("Point_toString")
    }

    @Test fun `enum uses names array not default toString`() {
        val r = transpileMain(
            decls = "enum class Color { RED, GREEN, BLUE }",
            body = "val c = Color.RED\nprintln(c)"
        )
        r.sourceContains("Color_names")
    }

    @Test fun `object default toString`() {
        val r = transpileMain(
            decls = "object Logger",
            body = "println(Logger)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("%s@%x")
    }

    @Test fun `interface default toString`() {
        val r = transpileMain(
            decls = """
                interface Shape { fun area(): Float }
                class Circle(private var r: Float) : Shape {
                    override fun area(): Float = r * r * 3.14f
                }
            """.trimIndent(),
            body = "val s: Shape = Circle(1f)\nprintln(s)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("@")
    }

    @Test fun `primitive int toString`() {
        val r = transpileMain("val x = 42\nprintln(x)")
        r.sourceContains("printf")
    }

    @Test fun `string toString returns itself`() {
        val r = transpileMain("val s = \"hello\"\nprintln(s)")
        r.sourceContains("printf")
    }

    // ── StringBuffer toString ─────────────────────────────────────

    @Test fun `StringBuffer constructor with null buffer`() {
        val r = transpileMain("val sb = StringBuffer(null, 0)")
        r.sourceContains("(ktc_StrBuf){NULL")
        r.sourceContains(", 0, 0}")
    }

    @Test fun `StringBuffer constructor with array pointer derives capacity`() {
        val r = transpileMain("""
            val chars = CharArray(256)
            val sb = StringBuffer(chars.asRef(), 0)
        """.trimIndent())
        r.sourceContains("(ktc_StrBuf){")
        r.sourceContains(".len")
    }

    @Test fun `StringBuffer field buffer maps to ptr`() {
        val r = transpileMain("""
            val sb = StringBuffer(null, 0)
            val p = sb.buffer
        """.trimIndent())
        r.sourceContains(".ptr")
    }

    @Test fun `StringBuffer field len maps to len`() {
        val r = transpileMain("""
            val sb = StringBuffer(null, 0)
            val l = sb.len
        """.trimIndent())
        r.sourceContains(".len")
    }

    @Test fun `data class toString with StringBuffer single pass`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int, val y: Int)",
            body = """
                val p = Point(1, 2)
                val sb = StringBuffer(null, 0)
                val s = p.toString(sb)
            """.trimIndent()
        )
        r.sourceContains("Point_toString")
        r.sourceContains("ktc_core_sb_to_string")
        val sbPattern = Regex("ktc_StrBuf \\w+_sb = \\{NULL, 0, 0\\};")
        assertFalse(sbPattern.containsMatchIn(r.source), "Should not contain two-pass StrBuf init pattern")
    }

    @Test fun `int toString with StringBuffer uses sb_append`() {
        val r = transpileMain("""
            val sb = StringBuffer(null, 0)
            val s = 42.toString(sb)
        """.trimIndent())
        r.sourceContains("ktc_core_sb_append_int")
        r.sourceContains("ktc_core_sb_to_string")
    }

    @Test fun `toString returns String when called via StringBuffer`() {
        val r = transpileMain(
            decls = "data class Vec2(val x: Float, val y: Float)",
            body = """
                val v = Vec2(1f, 2f)
                val sb = StringBuffer(null, 0)
                val result: String = v.toString(sb)
            """.trimIndent()
        )
        r.sourceContains("Vec2_toString")
        r.sourceContains("ktc_core_sb_to_string")
    }

    @Test fun `counting mode StringBuffer toString`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int)",
            body = """
                val p = Point(42)
                val sb = StringBuffer(null, 0)
                p.toString(sb)
            """.trimIndent()
        )
        r.sourceContains("Point_toString")
        // Should call toString only once (single pass, counting via NULL ptr)
    }

    @Test fun `counting mode then write pattern with StringBuffer`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int, val y: Int)",
            body = """
                val p = Point(1, 2)
                val sb = StringBuffer(null, 0)
                p.toString(sb)
                val buf = CharArray(sb.len + 1)
                val sb2 = StringBuffer(buf.asRef(), 0)
                p.toString(sb2)
            """.trimIndent()
        )
        // First toString: counting mode (NULL buffer)
        r.sourceContains("(ktc_StrBuf){NULL, 0, 0}")
        // Second toString: write mode with derived capacity
        r.sourceContains("buf.len")
        r.sourceContains("Point_toString")
    }

    // ── toString maxLength optimization ────────────────────────────

    @Test fun `bounded data class uses single-pass fixed buffer`() {
        val r = transpileMain(
            decls = "data class Vec2(val x: Float, val y: Float)",
            body = "val v = Vec2(1f, 2f)\nval s = v.toString()"
        )
        // Should NOT have two-pass pattern (no NULL-init StrBuf counting)
        assertFalse(Regex("ktc_StrBuf \\w+_sb = \\{NULL, 0, 0\\};").containsMatchIn(r.source),
            "Bounded data class should use fixed buffer, not two-pass")
        // Should use fixed stack buffer with computed size
        r.sourceContains("ktc_Char ")
        r.sourceContains("[")
        r.sourceContains("];")
    }

    @Test fun `data class with String field still uses two-pass`() {
        val r = transpileMain(
            decls = "data class Person(val name: String, val age: Int)",
            body = "val p = Person(\"Alice\", 30)\nval s = p.toString()"
        )
        // Should have two-pass (String field makes it unbounded)
        r.sourceContains("ktc_core_alloca")
    }

    @Test fun `nested bounded data class uses single-pass`() {
        val r = transpileMain(
            decls = """
                data class Point(val x: Int, val y: Int)
                data class Rect(val topLeft: Point, val bottomRight: Point)
            """.trimIndent(),
            body = """
                val r = Rect(Point(0, 0), Point(10, 20))
                val s = r.toString()
            """.trimIndent()
        )
        // No two-pass pattern for bounded nested data classes
        assertFalse(Regex("ktc_StrBuf \\w+_sb = \\{NULL, 0, 0\\};").containsMatchIn(r.source))
    }

    @Test fun `max output comment in header for bounded data class`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int, val y: Int)",
            body = "val p = Point(1, 2)"
        )
        r.headerContains("// max output: ")
        r.headerContains("chars")
    }

    @Test fun `no max output comment for unbounded data class`() {
        val r = transpileMain(
            decls = "data class Person(val name: String)",
            body = "val p = Person(\"A\")"
        )
        // Person has String field → unbounded → no max comment
        val hasComment = "// max output:" in r.header
        // The Person_toString line should NOT have the max comment
        val toStringLine = r.header.lines().find { it.contains("Person_toString") }
        assertFalse(
            toStringLine?.contains("// max output:") == true,
            "Person should not have max output comment (String field)"
        )
    }

    @Test fun `primitive toString uses tighter buffer sizes`() {
        // Int toString should use ~12 bytes instead of 32
        val r = transpileMain("val s = 42.toString()")
        r.sourceContains("ktc_core_sb_append_int")
        // Check that buffer is not 32 bytes (should be 12)
        assertFalse(Regex("char \\w+\\[32\\];").containsMatchIn(r.source),
            "Int toString should use 12-byte buffer, not 32")
    }

    @Test fun `boolean toString uses tight buffer`() {
        val r = transpileMain("val s = true.toString()")
        // Should use 6 bytes, not 8
        assertFalse(Regex("char \\w+\\[8\\];").containsMatchIn(r.source),
            "Boolean toString should use 6-byte buffer, not 8")
    }

    @Test fun `nullable bounded field uses correct buffer`() {
        val r = transpileMain(
            decls = "data class Opt(val x: Int?)",
            body = """
                val o = Opt(42)
                val s = o.toString()
            """.trimIndent()
        )
        // Nullable Int → max(Int, "null") = max(11, 4) = 11 + overhead → bounded
        assertFalse(Regex("ktc_StrBuf \\w+_sb = \\{NULL, 0, 0\\};").containsMatchIn(r.source))
    }
}
