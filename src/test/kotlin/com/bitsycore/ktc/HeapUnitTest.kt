package com.bitsycore.ktc

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * Tests for heap allocation via allocWith(Heap, ...), Ptr<T>, and Value<T>.
 */
class HeapUnitTest : TranspilerTestBase() {

    private val vec2Decl = "data class Vec2(var x: Float, var y: Float)"

    // ── Class.allocWith(Heap, ...) → heap constructor ───────────────────

    @Test fun heapAllocClass() {
        val r = transpileMainWithStdlib("val p = Vec2.allocWith(Heap, 10.0f, 20.0f)", decls = vec2Decl)
        r.sourceContains("test_Main_Vec2_primaryConstructor(10.0f, 20.0f)")
    }

    // ── Heap pointer field access (auto-deref through pointer) ──────

    @Test fun heapFieldRead() {
        val r = transpileMainWithStdlib(
            "val p = Vec2.allocWith(Heap, 10.0f, 20.0f)!!\nprintln(p.x)",
            decls = vec2Decl
        )
        r.sourceContains("p->x")
    }

    @Test fun heapFieldWrite() {
        val r = transpileMainWithStdlib(
            "val p = Vec2.allocWith(Heap, 10.0f, 20.0f)!!\np.x = 99.0f",
            decls = vec2Decl
        )
        r.sourceContains("p->x = 99.0f;")
    }

    // ── Same pointer, no copy ──────────────────────────────────────

    @Test fun heapValue() {
        val r = transpileMainWithStdlib(
            """
            val p = Vec2.allocWith(Heap, 10.0f, 20.0f)!!
            val v = p
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceNotContains("(*p)")
        r.sourceContains("= p;") // v = p (same pointer)
    }

    // ── .set() → update ──────────────────────────────────────────────

    @Test fun heapSet() {
        val r = transpileMainWithStdlib(
            "val p = Vec2.allocWith(Heap, 10.0f, 20.0f)!!\np.set(Vec2(1.0f, 2.0f))",
            decls = vec2Decl
        )
        r.sourceContains("*p =")
    }

    // ── Heap.freeMem ─────────────────────────────────────────────────

    @Test fun freeHeapPointer() {
        val r = transpileMainWithStdlib(
            "val p = Vec2.allocWith(Heap, 10.0f, 20.0f)!!\nHeap.freeMem(p)",
            decls = vec2Decl
        )
        r.sourceContains("freeMem")
    }

    // ── @Ptr T? — pointer nullable ──────────────────────────────────

    @Test fun heapAllocNullCheckSmartCast() {
        // After null check, smart cast should allow access
        val r = transpileMainWithStdlib("""
            val p: @Ptr Vec2? = Vec2.allocWith(Heap, 10.0f, 20.0f)
            if (p == null) return
            println(p.x)
        """, decls = vec2Decl)
        r.sourceContains("p == NULL")
        r.sourceContains("p->x")
    }

    @Test fun notNullAssertionEmitsCrash() {
        // !! on a nullable @Ptr should emit a NullPointerException check
        val r = transpileMainWithStdlib(
            "val p: @Ptr Vec2? = Vec2.allocWith(Heap, 10.0f, 20.0f)\nval q = p!!",
            decls = vec2Decl
        )
        r.sourceContains("NullPointerException")
        r.sourceContains("exit(1)")
    }

    @Test fun notNullAssertionOnVariable() {
        // !! on nullable variable should emit check
        val r = transpileMainWithStdlib("""
            var p: @Ptr Vec2? = Vec2.allocWith(Heap, 1.0f, 2.0f)
            val q = p!!
        """, decls = vec2Decl)
        r.sourceContains("NullPointerException")
    }

    @Test fun heapPtrNullable() {
        val r = transpileMainWithStdlib(
            """
                var q: @Ptr Vec2? = Vec2.allocWith(Heap, 3.0f, 4.0f)
                q = null
            """.trimIndent(),
            decls = vec2Decl
        )
        // @Ptr T? uses NULL for null
        r.sourceContains("NULL")
    }

    @Test fun heapPtrNullCheck() {
        val r = transpileMainWithStdlib(
            """
            var q: @Ptr Vec2? = Vec2.allocWith(Heap, 3.0f, 4.0f)
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
        val r = transpileMainWithStdlib(
            "val h = Vec2.allocWith(Heap, 1.0f, 2.0f)!!\nval p = h.ptr()",
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
        val r = transpileMainWithStdlib(
            """
            val h = Vec2.allocWith(Heap, 10.0f, 20.0f)!!
            val v = h.value()
            Heap.freeMem(h)
            println(v.x)
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceContains("v.x")
    }

    // ── Value<T> field write ─────────────────────────────────────────

    @Test fun valueFieldWrite() {
        val r = transpileMainWithStdlib(
            """
            val h = Vec2.allocWith(Heap, 10.0f, 20.0f)!!
            val v = h.value()
            v.x = 99.0f
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceContains("v.x = 99.0f;")
    }

    // ── Value<T>.value() → stack copy ────────────────────────────────

    @Test fun valueDeref() {
        val r = transpileMainWithStdlib(
            """
            val h = Vec2.allocWith(Heap, 10.0f, 20.0f)!!
            val v = h.value()
            """,
            decls = vec2Decl
        )
        r.sourceContains("(*h)")
    }

    // ── Value<T> method call — transparent delegation ────────────────

    @Test fun valueMethodCall() {
        val r = transpileMainWithStdlib("""
            val h = Counter.allocWith(Heap, 0)!!
            val v = h.value()
            v.inc()
        """, decls = "class Counter(var count: Int) {\n    fun inc() { count = count + 1 }\n}")
        r.sourceContains("test_Main_Counter_inc(&v)")
    }

    // ── Explicit Value<T> type annotation ────────────────────────────

    @Test fun explicitValueType() {
        val r = transpileMainWithStdlib(
            """
            val h = Vec2.allocWith(Heap, 1.0f, 2.0f)!!
            val v: Vec2 = h.value()
            println(v.x)
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceContains("v.x")
    }

    // ── Array<T>.allocWith(Heap, n) → heap array allocation ─────────────

    @Test fun heapAllocArrayInt() {
        val r = transpileMainWithStdlib("val buf = Array<Int>.allocWith(Heap, 10)")
        r.sourceContains("sizeof(ktc_Int) * (size_t)(10)")
    }

    // ── Body prop initialized from ctor param via allocWith ──────────

    @Test fun bodyPropInitFromCtorParam() {
        val decl = """
            class Buf(var capacity: Int) {
                var buf: @Ptr Array<Int> = Array<Int>.allocWith(Heap, capacity)
            }
        """
        val r = transpileMainWithStdlib("val b = Buf(16)", decls = decl)
        // struct field: ktc_VarArr_ktc_Int buf
        r.headerContains("ktc_VarArr_ktc_Int buf;")
        // _primaryConstructor initializes body prop from ctor param
        r.sourceContains("sizeof(ktc_Int) * (size_t)(capacity)")
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
