package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests that raw Array<T> types are blocked as function return types and class/object properties,
 * because they translate to stack-allocated VLAs that cannot safely outlive their scope.
 */
class ArrayTypeCheckUnitTest : TranspilerTestBase() {

    // ── Function return type checks ──────────────────────────────────

    @Test fun functionReturningArrayIntErrors() {
        transpileExpectError("""
            package test.Main
            fun bad(): Array<Int> { return Array<Int>(0) }
            fun main(args: Array<String>) {}
        """, "cannot return raw array type")
    }

    @Test fun functionReturningIntArrayErrors() {
        transpileExpectError("""
            package test.Main
            fun bad(): IntArray { return IntArray(0) }
            fun main(args: Array<String>) {}
        """, "cannot return raw array type")
    }

    @Test fun genericFunctionReturningArrayTErrors() {
        transpileExpectError("""
            package test.Main
            fun <T> bad(): Array<T> { return Array<T>(0) }
            fun main(args: Array<String>) {}
        """, "cannot return raw array type")
    }

    @Test fun methodReturningArrayIntErrors() {
        transpileExpectError("""
            package test.Main
            class Foo {
                fun bad(): Array<Int> { return Array<Int>(0) }
            }
            fun main(args: Array<String>) {}
        """, "cannot return raw array type")
    }

    // ── Class property checks ────────────────────────────────────────

    @Test fun classCtorPropertyWithArrayIntErrors() {
        transpileExpectError("""
            package test.Main
            class Foo(val arr: Array<Int>)
            fun main(args: Array<String>) {}
        """, "cannot have raw array type")
    }

    @Test fun classCtorPropertyWithIntArrayErrors() {
        transpileExpectError("""
            package test.Main
            class Foo(val arr: IntArray)
            fun main(args: Array<String>) {}
        """, "cannot have raw array type")
    }

    @Test fun classBodyPropertyWithArrayIntErrors() {
        transpileExpectError("""
            package test.Main
            class Foo {
                var arr: Array<Int> = Array<Int>(0)
            }
            fun main(args: Array<String>) {}
        """, "cannot have raw array type")
    }

    // ── Object property checks ───────────────────────────────────────

    @Test fun objectPropertyWithArrayIntErrors() {
        transpileExpectError("""
            package test.Main
            object Foo {
                var arr: Array<Int> = Array<Int>(0)
            }
            fun main(args: Array<String>) {}
        """, "cannot have raw array type")
    }

    // ── Allowed: @Ptr Array<T> ────────────────────

    @Test fun functionReturningPtrArrayIntSucceeds2() {
        val r = transpile("""
            package test.Main
            fun good(): @Ptr Array<Int> { val a = IntArray(4); return a.ptr() }
            fun main(args: Array<String>) {}
        """)
        r.sourceContains("test_Main_good(")
    }

    @Test fun functionReturningPtrArrayIntSucceeds() {
        val r = transpile("""
            package test.Main
            fun good(): @Ptr Array<Int> { val a = IntArray(4); return a.ptr() }
            fun main(args: Array<String>) {}
        """)
        r.sourceContains("test_Main_good(")
    }

    @Test fun classPropertyWithHeapArrayIntSucceeds() {
        val r = transpile("""
            package test.Main
            class Foo(var arr: @Ptr Array<Int>)
            fun main(args: Array<String>) {}
        """)
        r.headerContains("ktc_VarArr_ktc_Int arr;")
    }

    @Test fun classPropertyWithPtrArrayIntSucceeds() {
        val r = transpile("""
            package test.Main
            class Foo(var arr: @Ptr Array<Int>)
            fun main(args: Array<String>) {}
        """)
        r.headerContains("ktc_VarArr_ktc_Int arr;")
    }
}
