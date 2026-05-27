package com.bitsycore.ktc

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for enum classes.
 */
class EnumUnitTest : TranspilerTestBase() {

    private val colorDecl = "enum class Color { RED, GREEN, BLUE }"

    // ── Typedef ──────────────────────────────────────────────────────

    @Test fun enumTypedef() {
        val r = transpileMain("val c = Color.RED", decls = colorDecl)
        r.headerContains("KTC_ENUM(")
        r.headerContains("KTC_RELATED(RED)")
        r.headerContains("KTC_RELATED(GREEN)")
        r.headerContains("KTC_RELATED(BLUE)")
    }

    // ── Enum value usage ─────────────────────────────────────────────

    @Test fun enumValueAccess() {
        val r = transpileMain("val c = Color.RED\nprintln(c)", decls = colorDecl)
        r.sourceContains("test_Main_Color c = test_Main_Color_RED;")
    }

    // ── When on enum ─────────────────────────────────────────────────

    @Test fun whenOnEnum() {
        val r = transpileMain("""
            val c = Color.RED
            when (c) {
                Color.RED -> println("red")
                Color.GREEN -> println("green")
                Color.BLUE -> println("blue")
            }
        """, decls = colorDecl)
        r.sourceContains("test_Main_Color_RED")
        r.sourceContains("test_Main_Color_GREEN")
        r.sourceContains("test_Main_Color_BLUE")
    }

    // ── Enum not confused with nullable ──────────────────────────────

    @Test fun enumAccessNotNullError() {
        // Enum access via dot should NOT trigger nullable receiver error
        val r = transpileMain("val c = Color.RED", decls = colorDecl)
        r.sourceContains("test_Main_Color_RED")
    }

    // ── enumValues ────────────────────────────────────────────────────

    @Test fun enumValues() {
        val r = transpileMain("""
            val arr = enumValues<Color>()
            val first = arr[0]
        """, decls = colorDecl)
        r.sourceContains("test_Main_Color_values")
        r.sourceContains("test_Main_Color_RED")
    }

    @Test fun enumValuesExtern() {
        val r = transpile("""
            package test.Main
            enum class Color { RED, GREEN, BLUE }
            fun main(args: Array<String>) {
                val arr = enumValues<Color>()
                println(arr[0])
            }
        """)
        r.headerContains("extern const KTC_TYPE_NAME KTC_RELATED(values[3]);")
        r.headerContains("extern const ktc_Int KTC_RELATED(values\$len);")
        r.sourceContains("test_Main_Color_values[]")
        r.sourceContains("test_Main_Color_values\$len")
    }

    @Test fun enumValuesSize() {
        val r = transpileMain("""
            val sz = enumValues<Color>().size
            println(sz)
        """, decls = colorDecl)
        r.sourceContains("\$len")
    }

    // ── enumValueOf ───────────────────────────────────────────────────

    @Test fun enumValueOf() {
        val r = transpileMain("""
            val c = enumValueOf<Color>("GREEN")
            println(c)
        """, decls = colorDecl)
        r.sourceContains("test_Main_Color_valueOf")
        r.sourceContains("ktc_core_string_eq")
        assertTrue(
            r.source.contains("\"GREEN\"") || r.header.contains("\"GREEN\""),
            "Expected \"GREEN\" in source or header (may be deduped to static const)"
        )
    }

    @Test fun enumValueOfHeader() {
        val r = transpile("""
            package test.Main
            enum class Color { RED, GREEN, BLUE }
            fun main(args: Array<String>) {
                val c = enumValueOf<Color>("RED")
                println(c)
            }
        """)
        r.headerContains("KTC_METHOD(KTC_TYPE_NAME, valueOf)(ktc_String name);")
        r.headerContains("extern const KTC_TYPE_NAME KTC_RELATED(values[3]);")
    }

    // ── .name / .ordinal on enum values ───────────────────────────────

    @Test fun enumDotName() {
        val r = transpileMain("""
            val c = Color.GREEN
            val n: String = c.name
        """, decls = colorDecl)
        r.sourceContains("_names[")
        r.sourceContains("test_Main_Color_names")
    }

    @Test fun enumDotOrdinal() {
        val r = transpileMain("""
            val c = Color.BLUE
            val o: Int = c.ordinal
        """, decls = colorDecl)
        r.sourceContains("test_Main_Color_BLUE")
    }

    @Test fun enumDotNameDirect() {
        val r = transpileMain("""
            val n: String = Color.RED.name
        """, decls = colorDecl)
        r.sourceContains("_names[")
    }

    // ── @SimpleEnum opt-in (forces C-int form, must have no ctor/body) ──

    @Test fun simpleEnumAnnotationOk() {
        val r = transpile("""
            package test.Main
            @SimpleEnum enum class Dir { UP, DOWN }
            fun main(args: Array<String>) { val d = Dir.UP; println(d.name) }
        """.trimIndent())
        r.sourceContains("test_Main_Dir_UP")
        r.headerContains("KTC_ENUM(")
    }

    @Test fun simpleEnumCtorParamsError() {
        transpileExpectError("""
            package test.Main
            @SimpleEnum enum class Op(val sym: String) { PLUS, MINUS }
            fun main() {}
        """.trimIndent(), "no constructor parameters")
    }

    @Test fun simpleEnumBodyMembersError() {
        transpileExpectError("""
            package test.Main
            @SimpleEnum enum class Op { PLUS, MINUS; fun describe() = "?" }
            fun main() {}
        """.trimIndent(), "no body members")
    }

    // ── Full enums: struct representation with ctor params, per-entry args, body methods ──

    private val opDecl = """enum class Op(val sym: String) { PLUS("+"), MINUS("-"), TIMES("*") }"""

    @Test fun fullEnumStructTypedef() {
        val r = transpileMain("val op = Op.PLUS", decls = opDecl)
        r.headerContains("typedef struct test_Main_Op")
        r.headerContains("ktc_String sym;")
        r.headerContains("ktc_Int    ordinal;")
        r.headerContains("ktc_String name;")
        r.headerContains("extern const test_Main_Op test_Main_Op_PLUS;")
    }

    @Test fun fullEnumEntryInitializers() {
        val r = transpileMain("val op = Op.PLUS", decls = opDecl)
        r.sourceContains("const test_Main_Op test_Main_Op_PLUS = {")
        r.sourceContains("const test_Main_Op test_Main_Op_MINUS = {")
        r.sourceContains("const test_Main_Op test_Main_Op_TIMES = {")
    }

    @Test fun fullEnumCtorParamAccess() {
        val r = transpileMain("""
            val op = Op.PLUS
            val s = op.sym
        """, decls = opDecl)
        r.sourceContains(".sym")
    }

    @Test fun fullEnumEqualityViaOrdinal() {
        val r = transpileMain("""
            val a = Op.PLUS
            val b = Op.MINUS
            val same = a == b
        """, decls = opDecl)
        r.sourceContains(".ordinal ==")
    }

    @Test fun fullEnumWhenViaOrdinal() {
        val r = transpileMain("""
            val a = Op.PLUS
            when (a) {
                Op.PLUS  -> println("p")
                Op.MINUS -> println("m")
                Op.TIMES -> println("t")
            }
        """, decls = opDecl)
        r.sourceContains(".ordinal ==")
    }

    @Test fun fullEnumWithBodyMethod() {
        val r = transpile("""
            package test.Main
            enum class Op(val sym: String) {
                PLUS("+"), MINUS("-");
                fun pretty(): String = sym
            }
            fun main(args: Array<String>) {
                val op = Op.PLUS
                println(op.pretty())
            }
        """.trimIndent())
        r.headerContains("KTC_METHOD(ktc_String, pretty)")
        r.sourceContains("test_Main_Op_pretty(test_Main_Op \$self)")
    }

    @Test fun fullEnumMethodCallEmitsReceiver() {
        val r = transpile("""
            package test.Main
            enum class Op(val sym: String) {
                PLUS("+"), MINUS("-");
                fun render(): String = sym
            }
            fun main(args: Array<String>) {
                val op = Op.PLUS
                val s = op.render()
                println(s)
            }
        """.trimIndent())
        r.sourceContains("test_Main_Op_render(op)")
    }
}
