package com.bitsycore.ktc

import kotlin.test.Test
import kotlin.test.assertTrue

class SizeAnnotationUnitTest : TranspilerTestBase() {

    // ── Annotation parsing ────────────────────────────────────────────

    @Test fun annotationParsedOnReturnType() {
        val r = transpile("""
            package test.Main
            fun foo(): @Size(5) Array<Int> {
                var arr: IntArray = IntArray(5)
                return arr
            }
            fun main(args: Array<String>) {}
        """)
        r.sourceContains("test_Main_foo(")
        r.sourceNotContains("cannot return raw array type")
    }

    // ── Return type: @Size(N) Array<T> → void with $out ───────────────

    @Test fun sizedArrayReturnBecomesVoidWithOutParam() {
        val r = transpile("""
            package test.Main
            fun makeData(): @Size(3) IntArray {
                return intArrayOf(1, 2, 3)
            }
            fun main(args: Array<String>) {}
        """)
        r.headerContains("void test_Main_makeData(ktc_Int* \$out);")
    }

    @Test fun sizedArrayReturnNoLenOut() {
        val r = transpile("""
            package test.Main
            fun makeData(): @Size(3) IntArray {
                return intArrayOf(1, 2, 3)
            }
            fun main(args: Array<String>) {}
        """)
        r.headerNotContains("\$len_out")
    }

    // ── Struct field: @Size(N) → fixed array ──────────────────────────

    @Test fun classPropertySizedArrayEmitsFixedArray() {
        val r = transpile("""
            package test.Main
            class Buf(val buf: @Size(4) IntArray)
            fun main(args: Array<String>) {}
        """)
        r.headerContains("ktc_Int buf[4];")
        r.headerNotContains("buf\$len")
    }

    @Test fun classPropertySizedArrayNoLenField() {
        val r = transpile("""
            package test.Main
            class Buf(val buf: @Size(8) Array<Int>)
            fun main(args: Array<String>) {}
        """)
        r.headerContains("ktc_Int buf[8];")
        r.headerNotContains("buf\$len")
    }

    // ── Constructor param: @Size(N) → no $len companion ───────────────

    @Test fun sizedCtorParamNoLenCompanion() {
        val r = transpile("""
            package test.Main
            class Buf(val buf: @Size(3) IntArray)
            fun main(args: Array<String>) {}
        """)
        r.headerContains("ktc_Int* buf);")
        r.headerNotContains("buf\$len")
    }

    @Test fun sizedCtorParamMemcpyInit() {
        val r = transpile("""
            package test.Main
            class Buf(val buf: @Size(2) IntArray)
            fun main(args: Array<String>) {}
        """)
        r.sourceContains("memcpy(")
    }

    // ── Call site: caller allocates fixed array ───────────────────────

    @Test fun callerAllocatesLocalArrayForSizedReturn() {
        val r = transpileMain("""
            val arr = makeData()
        """, """
            fun makeData(): @Size(3) IntArray {
                return intArrayOf(1, 2, 3)
            }
        """)
        r.sourceContains("ktc_Int arr[3];")
        r.sourceContains("test_Main_makeData(")
    }

    @Test fun sizedArrayReturnCallNoLenVar() {
        val r = transpileMain("""
            val arr = makeData()
        """, """
            fun makeData(): @Size(2) IntArray {
                return intArrayOf(1, 2)
            }
        """)
        r.sourceContains("arr\$len")
    }

    // ── Method: @Size return ──────────────────────────────────────────

    @Test fun methodSizedArrayReturnVoidWithOut() {
        val r = transpile("""
            package test.Main
            class Calc {
                fun items(): @Size(3) IntArray {
                    return intArrayOf(1, 2, 3)
                }
            }
            fun main(args: Array<String>) {}
        """)
        r.headerContains("KTC_METHOD(void, items)(KTC_TYPE_NAME* \$self, ktc_Int* \$out);")
    }

    // ── Object property with @Size ────────────────────────────────────

    @Test fun objectPropertySizedArrayAllowed() {
        val r = transpile("""
            package test.Main
            object Config {
                val sizes: @Size(3) IntArray = intArrayOf(1, 2, 3)
            }
            fun main(args: Array<String>) {}
        """)
        r.sourceNotContains("cannot have raw array type")
    }

    // ── Negative tests: @Size on non-array type should still be raw ───

    @Test fun rawArrayWithoutSizeStillErrors() {
        transpileExpectError("""
            package test.Main
            fun bad(): Array<Int> { return Array<Int>(0) }
            fun main(args: Array<String>) {}
        """, "cannot return raw array type")
    }

    @Test fun classRawArrayPropertyStillErrors() {
        transpileExpectError("""
            package test.Main
            class Bad(val arr: Array<Int>)
            fun main(args: Array<String>) {}
        """, "cannot have raw array type")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun TranspileResult.headerNotContains(text: String, message: String? = null) {
        assertTrue(
            !header.contains(text),
            message ?: "Expected C header NOT to contain:\n  «$text»\n\nActual header:\n$header"
        )
    }
}
