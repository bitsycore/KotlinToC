package com.bitsycore.ktc

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * Tests for heap allocation via allocWith(Heap, ...), Ptr<T>, and Value<T>.
 */
class HeapUnitTest : TranspilerTestBase() {

    private val vec2Decl = "data class Vec2(var x: Float, var y: Float)"

    // ── Class(...).allocWith(Heap) → heap constructor ───────────────────

    @Test fun heapAllocClass() {
        val r = transpileMainWithStdlib("val p = Vec2(10.0f, 20.0f).allocWith(Heap)", decls = vec2Decl)
        r.sourceContains("test_Main_Vec2_primaryConstructor(10.0f, 20.0f)")
    }

    // ── Heap pointer field access (auto-deref through pointer) ──────

    @Test fun heapFieldRead() {
        val r = transpileMainWithStdlib(
            "val p = Vec2(10.0f, 20.0f).allocWith(Heap)!!\nprintln(p.x)",
            decls = vec2Decl
        )
        r.sourceContains("p->x")
    }

    @Test fun heapFieldWrite() {
        val r = transpileMainWithStdlib(
            "val p = Vec2(10.0f, 20.0f).allocWith(Heap)!!\np.x = 99.0f",
            decls = vec2Decl
        )
        r.sourceContains("p->x = 99.0f;")
    }

    // ── Same pointer, no copy ──────────────────────────────────────

    @Test fun heapValue() {
        val r = transpileMainWithStdlib(
            """
            val p = Vec2(10.0f, 20.0f).allocWith(Heap)!!
            val v = p
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceNotContains("(*p)")
        r.sourceContains("= p;") // v = p (same pointer)
    }

    // ── p.value = x → update ─────────────────────────────────────────

    @Test fun heapSet() {
        val r = transpileMainWithStdlib(
            "val p = Vec2(10.0f, 20.0f).allocWith(Heap)!!\np.value = Vec2(1.0f, 2.0f)",
            decls = vec2Decl
        )
        r.sourceContains("*p =")
    }

    // ── Heap.freeMem ─────────────────────────────────────────────────

    @Test fun freeHeapPointer() {
        val r = transpileMainWithStdlib(
            "val p = Vec2(10.0f, 20.0f).allocWith(Heap)!!\nHeap.freeMem(p)",
            decls = vec2Decl
        )
        r.sourceContains("freeMem")
    }

    // ── @Ptr T? — pointer nullable ──────────────────────────────────

    @Test fun heapAllocNullCheckSmartCast() {
        // After null check, smart cast should allow access
        val r = transpileMainWithStdlib("""
            val p: @Ptr Vec2? = Vec2(10.0f, 20.0f).allocWith(Heap)
            if (p == null) return
            println(p.x)
        """, decls = vec2Decl)
        r.sourceContains("p == NULL")
        r.sourceContains("p->x")
    }

    @Test fun notNullAssertionEmitsCrash() {
        // !! on a nullable @Ptr should emit a NullPointerException check
        val r = transpileMainWithStdlib(
            "val p: @Ptr Vec2? = Vec2(10.0f, 20.0f).allocWith(Heap)\nval q = p!!",
            decls = vec2Decl
        )
        r.sourceContains("NullPointerException")
        r.sourceContains("exit(1)")
    }

    @Test fun notNullAssertionOnVariable() {
        // !! on nullable variable should emit check
        val r = transpileMainWithStdlib("""
            var p: @Ptr Vec2? = Vec2(1.0f, 2.0f).allocWith(Heap)
            val q = p!!
        """, decls = vec2Decl)
        r.sourceContains("NullPointerException")
    }

    @Test fun heapPtrNullable() {
        val r = transpileMainWithStdlib(
            """
                var q: @Ptr Vec2? = Vec2(3.0f, 4.0f).allocWith(Heap)
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
            var q: @Ptr Vec2? = Vec2(3.0f, 4.0f).allocWith(Heap)
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
            "val h = Vec2(1.0f, 2.0f).allocWith(Heap)!!\nval p = h.ptr()",
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

    // ── Ptr write via .value = x ─────────────────────────────────────

    @Test fun ptrSet() {
        val r = transpileMain(
            "val v = Vec2(1.0f, 2.0f)\nval p = v.ptr()\np.value = Vec2(3.0f, 4.0f)",
            decls = vec2Decl
        )
        r.sourceContains("*p =")
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
            val h = Vec2(10.0f, 20.0f).allocWith(Heap)!!
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
            val h = Vec2(10.0f, 20.0f).allocWith(Heap)!!
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
            val h = Vec2(10.0f, 20.0f).allocWith(Heap)!!
            val v = h.value()
            """,
            decls = vec2Decl
        )
        r.sourceContains("(*h)")
    }

    // ── Value<T> method call — transparent delegation ────────────────

    @Test fun valueMethodCall() {
        val r = transpileMainWithStdlib("""
            val h = Counter(0).allocWith(Heap)!!
            val v = h.value()
            v.inc()
        """, decls = "class Counter(var count: Int) {\n    fun inc() { count = count + 1 }\n}")
        r.sourceContains("test_Main_Counter_inc(&v)")
    }

    // ── Explicit Value<T> type annotation ────────────────────────────

    @Test fun explicitValueType() {
        val r = transpileMainWithStdlib(
            """
            val h = Vec2(1.0f, 2.0f).allocWith(Heap)!!
            val v: Vec2 = h.value()
            println(v.x)
            """.trimIndent(),
            decls = vec2Decl
        )
        r.sourceContains("v.x")
    }

    // ── Array<T>(n).allocWith(Heap) → heap array allocation ─────────────

    @Test fun heapAllocArrayInt() {
        val r = transpileMainWithStdlib("val buf = Array<Int>(10).allocWith(Heap)")
        r.sourceContains("sizeof(ktc_Int) * (size_t)(10)")
    }

    // ── Body prop initialized from ctor param via allocWith ──────────

    @Test fun bodyPropInitFromCtorParam() {
        val decl = """
            class Buf(var capacity: Int) {
                var buf: @Ptr Array<Int> = Array<Int>(capacity).allocWith(Heap)
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

    // ── @Ptr Allocator forwarding (regression for CallArgs trampoline-rewrap bug) ────

    // When a function takes @Ptr Allocator and forwards it to another @Ptr-Allocator
    // call site (here: allocWith), the codegen must forward the existing IfacePtr
    // directly — NOT wrap it again with a bogus ktc_std_Allocator_Allocator_vt.
    @Test fun ptrAllocatorForwardedToAllocWith() {
        val r = transpileMainWithStdlib(
            body  = "val p = mk(Heap)!!",
            decls = "$vec2Decl\nfun mk(a: @Ptr Allocator): @Ptr Vec2 = Vec2(1.0f, 2.0f).allocWith(a)"
        )
        // The bug emitted a reference to a non-existent vtable; ensure it's gone.
        r.sourceNotContains("ktc_std_Allocator_Allocator_vt")
        // And the trampoline path through the allocator's vtable is emitted.
        r.sourceContains("(ktc_std_Allocator_vt*)")
    }

    // Same forwarding pattern through ArrayList<T>(allocator, n) — exercises the
    // generic-class ctor arg path in CallArgs.
    @Test fun ptrAllocatorForwardedToGenericCtor() {
        val r = transpileMainWithStdlib(
            body  = "val list = mk(Heap)",
            decls = "fun mk(a: @Ptr Allocator): ArrayList<Int> = ArrayList<Int>(a, 4)"
        )
        r.sourceNotContains("ktc_std_Allocator_Allocator_vt")
    }

    // Two-level forwarding: the inner function takes @Ptr Allocator from the outer,
    // which itself got it from the call site. Trampoline must survive both hops.
    @Test fun ptrAllocatorForwardedTwoLevels() {
        val r = transpileMainWithStdlib(
            body  = "val p = outer(Heap)!!",
            decls = """
                $vec2Decl
                fun inner(a: @Ptr Allocator): @Ptr Vec2 = Vec2(1.0f, 2.0f).allocWith(a)
                fun outer(a: @Ptr Allocator): @Ptr Vec2 = inner(a)
            """.trimIndent()
        )
        r.sourceNotContains("ktc_std_Allocator_Allocator_vt")
    }

    // Heap-allocated Array<T>(size) { init } via allocWith — the lambda init must
    // actually run (was silently dropped before the fix).
    @Test fun heapArrayInitLambdaRuns() {
        val r = transpileMainWithStdlib(
            "val sq = Array<Int>(4) { it -> it * it }.allocWith(Heap)"
        )
        // Loop variable + array-element assignment must be present.
        r.sourceContains("for (ktc_Int it = 0; it < (4); it++)")
        r.sourceMatches(Regex("""\$\d+_ptr\[it\] = \(it \* it\);"""))
    }

    // Sanity: the no-lambda heap form does NOT emit an init loop.
    @Test fun heapArrayNoLambdaSkipsLoop() {
        val r = transpileMainWithStdlib(
            "val a = Array<Int>(8).allocWith(Heap)"
        )
        r.sourceNotContains("for (ktc_Int it = 0;")
    }

    // ── Known limitations (notYetImpl) ───────────────────────────────────

    // User-package class implementing a stdlib interface (Allocator) injects the
    // user type into the stdlib package header's CLS_TYPES macro before the user
    // type is declared, producing 'unknown type name' compile errors. Existing
    // Allocator impls in stdlib (Heap object, Arena class) work; user classes do not.
    @Test fun userClassImplementingStdlibAllocator_brokenCrossPackage() {
        notYetImpl("user-package class : Allocator triggers cross-package CLS_TYPES forward-decl bug")
    }

    // Pair<T,T>.toList(allocator) — generic extension on a stdlib generic class.
    @Test fun pairToListWithAllocator() {
        val r = transpileMainWithStdlib("""
            val pair = 10 to 20
            val list = pair.toList(Heap)
        """)
        // Receiver fields must be qualified with $self.
        r.sourceContains("\$self.first")
        r.sourceContains("\$self.second")
        // Return type is the iface trampoline for List<Int>, NOT a user-package List type.
        r.sourceNotContains("test_Main_List_")
    }

    // Multi-statement lambda body: a VarDeclStmt + tail expression. The emitArrayInitLambda
    // helper must handle non-trivial bodies (mirrors the stack-array form).
    @Test fun heapArrayInitLambdaMultiStmt() {
        val r = transpileMainWithStdlib("""
            val a = Array<Int>(4) { i ->
                val doubled = i * 2
                doubled + 1
            }.allocWith(Heap)
        """)
        r.sourceContains("for (ktc_Int i = 0; i < (4); i++)")
        r.sourceContains("doubled =")
        // The final expression is what writes the slot.
        r.sourceMatches(Regex("""_ptr\[i\] = \(doubled \+ 1\);"""))
    }

    // ── Pointer↔value boundary check (Task 18) ──────────────────────

    // Declaring a @Ptr T variable with a value-typed initializer is rejected:
    // the user must write .ptr() explicitly.
    @Test fun ptrDeclFromValueIsRejected() {
        val ex = assertThrows<IllegalStateException> {
            transpileMain("""
                val v = Vec2(1.0f, 2.0f)
                val p: @Ptr Vec2 = v
            """, decls = vec2Decl)
        }
        assert(ex.message!!.contains("boundary")) { "expected boundary error, got: ${ex.message}" }
        assert(ex.message!!.contains(".ptr()")) { "expected .ptr() fix-it, got: ${ex.message}" }
    }

    // Declaring a value T variable with a pointer-typed initializer is rejected:
    // the user must write .value() explicitly.
    @Test fun valueDeclFromPtrIsRejected() {
        val ex = assertThrows<IllegalStateException> {
            transpileMain("""
                val v = Vec2(1.0f, 2.0f)
                val p = v.ptr()
                val w: Vec2 = p
            """, decls = vec2Decl)
        }
        assert(ex.message!!.contains("boundary")) { "expected boundary error, got: ${ex.message}" }
        assert(ex.message!!.contains(".value()")) { "expected .value() fix-it, got: ${ex.message}" }
    }

    // Explicit .ptr() at the boundary is accepted with no error.
    @Test fun ptrDeclWithExplicitPtrIsOk() {
        val r = transpileMain("""
            val v = Vec2(1.0f, 2.0f)
            val p: @Ptr Vec2 = v.ptr()
        """, decls = vec2Decl)
        r.sourceContains("Vec2* p =")
    }
}
