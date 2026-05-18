package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for interfaces: declaration, implementation, virtual dispatch.
 */
class InterfaceUnitTest : TranspilerTestBase() {

    private val shapeDecls = """
        interface Shape {
            fun area(): Float
            fun describe(): String
        }
        class Circle(val radius: Float) : Shape {
            override fun area(): Float = 3.14f * radius * radius
            override fun describe(): String = "Circle"
        }
        class Square(val side: Float) : Shape {
            override fun area(): Float = side * side
            override fun describe(): String = "Square"
        }
    """

    // ── Interface vtable ─────────────────────────────────────────────

    @Test fun interfaceVtableStruct() {
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls)
        r.headerContains("test_Main_Shape_vt")
    }

    @Test fun interfaceMethodGrouping() {
        val multiIfaceDecls = """
            interface Shape { fun area(): Float; fun name(): String }
            interface Labeled { fun label(): String }
            class Circle(val radius: Float) : Shape, Labeled {
                override fun area(): Float = 3.14f * radius * radius
                override fun name(): String = "Circle"
                override fun label(): String = "circle-label"
            }
        """
        val r = transpile("""
            package test.Main
            $multiIfaceDecls
            fun main(args: Array<String>) { val c = Circle(1.0f) }
        """)
        // Interface methods appear under per-interface sections
        r.sourceContains("implements Shape")
        r.sourceContains("implements Labeled")
        r.sourceNotContains("//    methods    //")
        // Each vtable+cast is grouped under a "cast to X" section
        r.sourceContains("cast to Shape")
        r.sourceContains("cast to Labeled")
        r.sourceContains("cast to Any")
        r.sourceNotContains("//    as    //")
        // Exactly one blank line between sections (no triple newlines within Circle's block)
        val vCircleSrc = r.source.substringAfter("class Circle").substringBefore("#include _package_")
        kotlin.test.assertTrue(!vCircleSrc.contains("\n\n\n"), "Double blank lines found in Circle source block")
    }

    @Test fun interfaceFatPointerStruct() {
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls)
        // KTC_INTERFACE macro generates the tagged union; CLS_TYPES lists all implementors
        r.headerContains("KTC_INTERFACE({")
        r.headerContains("X(test_Main_Circle, test_Main_Circle)")
        r.headerContains("X(test_Main_Square, test_Main_Square)")
    }

    // ── Interface dispatch ───────────────────────────────────────────

    @Test fun interfaceMethodCall() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            fun printShape(s: Shape) {
                println(s.area())
            }
            fun main(args: Array<String>) {
                val c = Circle(5.0f)
                printShape(c)
            }
        """)
        // Virtual dispatch: s.vt->area((void*)&s.data) — multi-impl interface uses union .data
        r.sourceContains("s.vt->area((void*)&s.data)")
    }

    // ── Auto-wrap class → interface ──────────────────────────────────

    @Test fun autoWrapClassToInterface() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            fun printShape(s: Shape) {
                println(s.area())
            }
            fun main(args: Array<String>) {
                val c = Circle(5.0f)
                printShape(c)
            }
        """)
        // Circle passed to Shape param → wrapped with vtable
        r.sourceContains("test_Main_Circle_as_Shape")
    }

    // ── Multiple implementations ─────────────────────────────────────

    @Test fun multipleImplementations() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            fun printShape(s: Shape) {
                println(s.describe())
            }
            fun main(args: Array<String>) {
                printShape(Circle(5.0f))
                printShape(Square(3.0f))
            }
        """)
        r.sourceContains("test_Main_Circle_as_Shape")
        r.sourceContains("test_Main_Square_as_Shape")
    }

    // ── Type ID system ────────────────────────────────────────────────

    @Test fun typeIdDefineInHeader() {
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls)
        // TYPE_IDs are now declared via KTC_TYPE_ID(N) macro; symbol names are generated at preprocess time
        r.headerContains("KTC_TYPE_ID(")
    }

    @Test fun typeIdFieldInStruct() {
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls)
        r.headerContains("ktc_core_AnySupertype __base;")
    }

    @Test fun typeIdInConstructor() {
        // Classes with all ctor props use compound literal: return (Circle){TYPE_ID, radius};
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls) 
        r.sourceContains("return (test_Main_Circle){")
        r.sourceContains("test_Main_Circle_TYPE_ID")
    }

    @Test fun isCheckClass() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            class Box(val w: Float)
            fun main(args: Array<String>) {
                val c = Circle(5.0f)
                if (c is Circle) println("yes")
            }
        """)
        r.sourceContains("KTC_GET_TYPEID(c.__base.typeId) == test_Main_Circle_TYPE_ID")
    }

    @Test fun isCheckInterface() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            fun main(args: Array<String>) {
                val s: Shape = Circle(5.0f)
                if (s is Shape) println("yes")
            }
        """)
        r.sourceContains("KTC_GET_TYPEID(s.__base.typeId) == test_Main_Circle_TYPE_ID ||")
    }

    @Test fun negatedIsCheck() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            class Box(val w: Float)
            fun main(args: Array<String>) {
                val b = Box(3.0f)
                if (b !is Circle) println("no")
            }
        """)
        r.sourceContains("!(KTC_GET_TYPEID(b.__base.typeId) == test_Main_Circle_TYPE_ID)")
    }

    // ── Override enforcement ──────────────────────────────────────────

    @Test fun validOverridePasses() {
        val decls = """
            interface Foo {
                fun bar(): Int
            }
            class Impl : Foo {
                override fun bar(): Int = 42
            }
        """.trimIndent()
        val r = transpileMain("val f = Impl()", decls = decls)
        r.sourceContains("Impl_bar")
    }

    @Test fun missingOverrideKeywordError() {
        val decls = """
            interface Foo {
                fun bar(): Int
            }
            class Impl : Foo {
                fun bar(): Int = 42
            }
        """.trimIndent()
        transpileMainExpectError("val f = Impl()", "must be marked 'override'", decls = decls)
    }

    @Test fun missingInterfaceMethodError() {
        val decls = """
            interface Foo {
                fun bar(): Int
                fun baz(): String
            }
            class Impl : Foo {
                override fun bar(): Int = 42
            }
        """.trimIndent()
        transpileMainExpectError("val f = Impl()", "must implement 'baz'", decls = decls)
    }

    @Test fun bogusOverrideError() {
        val decls = """
            class Impl {
                override fun bar(): Int = 42
            }
        """.trimIndent()
        transpileMainExpectError("val f = Impl()", "does not override any interface method", decls = decls)
    }

    @Test fun overrideMethodNotInInterface() {
        val decls = """
            interface Foo {
                fun bar(): Int
            }
            class Impl : Foo {
                override fun bar(): Int = 42
                override fun baz(): String = "hello"
            }
        """.trimIndent()
        transpileMainExpectError("val f = Impl()", "does not override any interface method", decls = decls)
    }

    @Test fun ifaceBlockBannerFormat() {
        val r = transpile("""
            package test.Main
            interface Shape { fun area(): Float }
            interface Drawable : Shape { fun draw() }
            class Circle(val radius: Float) : Drawable {
                override fun area(): Float = 3.14f * radius * radius
                override fun draw() {}
            }
            fun main(args: Array<String>) { val c = Circle(1.0f) }
        """)
        // Both interfaces should have banner headers and footers
        r.headerContains("* interface Shape")
        r.headerContains("* END interface Shape")
        r.headerContains("* interface Drawable")
        r.headerContains("* END interface Drawable")
        // KTC_INTERFACE macro combines vtable and tagged union
        r.headerContains("KTC_INTERFACE({")
        r.headerContains("CLS_TYPES")
        // KTC_INTERFACE should appear after the banner header
        val vShapeIdx = r.header.indexOf("* interface Shape\n")
        val vIfaceIdx = r.header.indexOf("KTC_INTERFACE({", vShapeIdx)
        assert(vIfaceIdx > vShapeIdx) {
            "KTC_INTERFACE should appear after the banner; header:\n${r.header}"
        }
    }
}
