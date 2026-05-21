package com.bitsycore.ktc

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * Tests for Heap<T> (heap-allocated objects), Ptr<T>, Value<T>, HeapAlloc, HeapFree.
 */
class HeapUnitTest : TranspilerTestBase() {

    private val vec2Decl = "data class Vec2(var x: Float, var y: Float)"

    // ── HeapAlloc<Class> → heap constructor ─────────────────────────────

    @Test fun heapAllocClass() {
        val r = transpileMain("val p = HeapAlloc<Vec2>(10.0f, 20.0f)", decls = vec2Decl)
        r.sourceContains("test_Main_Vec2_primaryConstructor(10.0f, 20.0f)")
        r.sourceContains("malloc(sizeof(test_Main_Vec2))")
    }

    // ── Heap<T> field access (auto-deref through pointer) ──────────

    @Test fun heapFieldRead() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!\nprintln(p.x)",
            decls = vec2Decl
        )
        r.sourceContains("p->x")
    }

    @Test fun heapFieldWrite() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!\np.x = 99.0f",
            decls = vec2Decl
        )
        r.sourceContains("p->x = 99.0f;")
    }

    // ── .value() → Value<T> (same pointer, no copy) ────────────────

    @Test fun heapValue() {
        val r = transpileMain(
            """
            val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!
            val v = p
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceNotContains("(*p)")
        r.sourceContains("= p;") // v = p (same pointer)
    }

    // ── .set() → update ──────────────────────────────────────────────

    @Test fun heapSet() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)\np.set(Vec2(1.0f, 2.0f))",
            decls = vec2Decl
        )
        r.sourceContains("*p =")
    }

    // ── HeapFree ─────────────────────────────────────────────────────

    @Test fun freeHeapPointer() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)\nHeapFree(p)",
            decls = vec2Decl
        )
        r.sourceContains("free(p)")
    }

    // ── Typed raw pointer ────────────────────────────────────────────

    @Test fun typedPointerHeapAlloc() {
        val r = transpileMain("val ints = HeapAlloc<Int>(5)")
        r.sourceContains("(ktc_Int*)malloc(sizeof(ktc_Int) *")
    }

    @Test fun typedPointerIndexRead() {
        val r = transpileMain("val ints = HeapAlloc<Int>(5)!!\nprintln(ints[2])")
        r.sourceContains("ints[2]")
    }

    @Test fun typedPointerIndexWrite() {
        val r = transpileMain("val ints = HeapAlloc<Int>(5)!!\nints[0] = 42")
        r.sourceContains("ints[0] = 42;")
    }

    // ── Raw HeapAlloc ───────────────────────────────────────────────────

    @Test fun rawHeapAlloc() {
        val r = transpileMain("val buf = HeapAlloc(1024)")
        r.sourceContains("malloc((size_t)(1024))")
    }

    @Test fun rawHeapArrayResize() {
        val r = transpileMain("val buf = HeapAlloc(1024)\nval buf2 = HeapArrayResize(buf, 2048)")
        r.sourceContains("realloc(buf, (size_t)(2048))")
    }

    // ── Heap<T>? — pointer nullable ──────────────────────────────────

    @Test fun heapAllocReturnsNullable() {
        // HeapAlloc without !! should be nullable — accessing .x should error
        val ex = assertThrows<IllegalStateException> {
            transpileMain("val p = HeapAlloc<Vec2>(10.0f, 20.0f)\nprintln(p.x)", decls = vec2Decl)
        }
        assert(ex.message!!.contains("safe"))
    }

    @Test fun heapAllocNullCheckSmartCast() {
        // After null check, smart cast should allow access
        val r = transpileMain("""
            val p = HeapAlloc<Vec2>(10.0f, 20.0f)
            if (p == null) return
            println(p.x)
        """, decls = vec2Decl)
        r.sourceContains("p == NULL")
        r.sourceContains("p->x")
    }

    @Test fun notNullAssertionEmitsCrash() {
        // !! on HeapAlloc should emit NullPointerException check
        val r = transpileMain("val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!", decls = vec2Decl)
        r.sourceContains("NullPointerException")
        r.sourceContains("exit(1)")
    }

    @Test fun notNullAssertionOnVariable() {
        // !! on nullable variable should emit check
        val r = transpileMain("""
            var p: @Ptr Vec2? = HeapAlloc<Vec2>(1.0f, 2.0f)
            val q = p!!
        """, decls = vec2Decl)
        r.sourceContains("NullPointerException")
    }

    @Test fun heapPtrNullable() {
        val r = transpileMain(
            """
                var q: @Ptr Vec2? = HeapAlloc<Vec2>(3.0f, 4.0f)
                q = null
            """.trimIndent(),
            decls = vec2Decl
        )
        // Heap<T>? uses NULL for null
        r.sourceContains("NULL")
    }

    @Test fun heapPtrNullCheck() {
        val r = transpileMain(
            """
            var q: @Ptr Vec2? = HeapAlloc<Vec2>(3.0f, 4.0f)
            if (q != null) {
                println(q?.x)
            }
            """,
            decls = vec2Decl
        )
        r.sourceContains("q != NULL")
    }

    // ── Heap .ptr() ────────────────────────────────────────────────

    @Test fun heapptr() {
        val r = transpileMain(
            "val h = HeapAlloc<Vec2>(1.0f, 2.0f)!!\nval p = h.ptr()",
            decls = vec2Decl
        )
        // ptr() is identity — same pointer, just changes type
        r.sourceContains("= h;")
    }

    // ══════════════════════════════════════════════════════════════════
    // Ptr<T> tests
    // ══════════════════════════════════════════════════════════════════

    // ── Ptr from stack (.ptr()) ────────────────────────────────────

    @Test fun stackptr() {
        val r = transpileMain(
            "val v = Vec2(1.0f, 2.0f)\nval p = v.ptr()",
            decls = vec2Decl
        )
        r.sourceContains("&v")
    }

    // ── Ptr field access (auto-deref) ──────────────────────────────

    @Test fun ptrFieldAccess() {
        val r = transpileMain(
            "val v = Vec2(5.0f, 6.0f)\nval p = v.ptr()\nprintln(p.x)",
            decls = vec2Decl
        )
        r.sourceContains("p->x")
    }

    // ── Ptr.value() → Value<T> ───────────────────────────────────────

    @Test fun ptrValue() {
        val r = transpileMain(
            """
                val v = Vec2(1.0f, 2.0f)
                val p = v.ptr()
                val vr = p.value()
            """,
            decls = vec2Decl
        )
        r.sourceContains("(*p)")
    }

    // ── Ptr.set() ────────────────────────────────────────────────────

    @Test fun ptrSet() {
        val r = transpileMain(
            "val v = Vec2(1.0f, 2.0f)\nval p = v.ptr()\np.set(Vec2(3.0f, 4.0f))",
            decls = vec2Decl
        )
        r.sourceContains("*")
    }

    // ── Ptr field access through .value() ────────────────────────────

    @Test fun ptrValueFieldAccess() {
        val r = transpileMain(
            "val v = Vec2(5.0f, 6.0f)\nval p = v.ptr()\nprintln(p.value().x)",
            decls = vec2Decl
        )
        r.sourceContains("->x")
    }

    // ══════════════════════════════════════════════════════════════════
    // Value<T> tests
    // ══════════════════════════════════════════════════════════════════

    // ── Value<T> from .value() — transparent field access ────────────

    @Test fun valueFieldAccess() {
        val r = transpileMain(
            """
            val h = HeapAlloc<Vec2>(10.0f, 20.0f)!!
            val v = h.value()
            HeapFree(h)
            println(v.x)
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceContains("v.x")
    }

    // ── Value<T> field write ─────────────────────────────────────────

    @Test fun valueFieldWrite() {
        val r = transpileMain(
            """
            val h = HeapAlloc<Vec2>(10.0f, 20.0f)!!
            val v = h.value()
            v.x = 99.0f
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceContains("v.x = 99.0f;")
    }

    // ── Value<T>.value() → stack copy ────────────────────────────────

    @Test fun valueDeref() {
        val r = transpileMain(
            """
            val h = HeapAlloc<Vec2>(10.0f, 20.0f)!!
            val v = h.value()
            """,
            decls = vec2Decl
        )
        r.sourceContains("(*h)")
    }

    // ── Value<T> method call — transparent delegation ────────────────

    @Test fun valueMethodCall() {
        val r = transpile("""
            package test.Main
            class Counter(var count: Int) {
                fun inc() { count = count + 1 }
            }
            fun main(args: Array<String>) {
                val h = HeapAlloc<Counter>(0)!!
                val v = h.value()
                v.inc()
            }
        """)
        r.sourceContains("test_Main_Counter_inc(&v)")
    }

    // ── Explicit Value<T> type annotation ────────────────────────────

    @Test fun explicitValueType() {
        val r = transpileMain(
            """
            val h = HeapAlloc<Vec2>(1.0f, 2.0f)!!
            val v: Vec2 = h.value()
            println(v.x)
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceContains("v.x")
    }

    // ── HeapAlloc<Array<T>>(n) → typed array allocation ─────────────────

    @Test fun heapAllocArrayInt() {
        val r = transpileMain("val buf = HeapAlloc<Array<Int>>(10)")
        r.sourceContains("(ktc_Int*)malloc(sizeof(ktc_Int) * (size_t)(10))")
    }

    @Test fun heapAllocArrayFloat() {
        val r = transpileMain("val buf = HeapAlloc<Array<Float>>(5)")
        r.sourceContains("(ktc_Float*)malloc(sizeof(ktc_Float) * (size_t)(5))")
    }

    @Test fun heapAllocArrayLong() {
        val r = transpileMain("val buf = HeapAlloc<Array<Long>>(3)")
        r.sourceContains("(ktc_Long*)malloc(sizeof(ktc_Long) * (size_t)(3))")
    }

    // ── HeapAlloc<T>() with no args → single element allocation ─────────

    @Test fun heapAllocSingleInt() {
        val r = transpileMain("val p = HeapAlloc<Int>()")
        r.sourceContains("(ktc_Int*)malloc(sizeof(ktc_Int))")
    }

    @Test fun heapAllocSingleFloat() {
        val r = transpileMain("val p = HeapAlloc<Float>()")
        r.sourceContains("(ktc_Float*)malloc(sizeof(ktc_Float))")
    }

    // ── HeapArrayResize<Array<T>>(ptr, n) → typed array realloc ──────────────

    @Test fun heapArrayResizeInt() {
        val r = transpileMain("val buf = HeapAlloc<Array<Int>>(10)\nval buf2 = HeapArrayResize<Array<Int>>(buf, 20)")
        r.sourceContains("(ktc_Int*)realloc((buf).ptr, sizeof(ktc_Int) * (size_t)(20))")
    }

    // ── HeapArrayZero<Array<T>>(n) → typed array calloc ─────────────────────

    @Test fun heapZeroArrayInt() {
        val r = transpileMain("val buf = HeapArrayZero<Array<Int>>(10)")
        r.sourceContains("(ktc_Int*)calloc((size_t)(10), sizeof(ktc_Int))")
    }

    // ── Body prop with initializer referencing ctor param ────────────

    @Test fun bodyPropInitFromCtorParam() {
        val decl = """
            class Buf(var capacity: Int) {
                var buf: @Ptr Array<Int> = HeapAlloc<Array<Int>>(capacity)
            }
        """
        val r = transpileMain("val b = Buf(16)", decls = decl)
        // struct field: ktc_VarArr_ktc_Int buf
        r.headerContains("ktc_VarArr_ktc_Int buf;")
        // _primaryConstructor initializes body prop from ctor param
        r.sourceContains("(ktc_Int*)malloc(sizeof(ktc_Int) * (size_t)(capacity))")
    }

    @Test fun bodyPropInitConstant() {
        val decl = """
            class Counter(var name: String) {
                var count: Int = 0
            }
        """
        val r = transpileMain("val c = Counter(\"hello\")", decls = decl)
        r.sourceContains("\$self.count = 0;")
    }
}
