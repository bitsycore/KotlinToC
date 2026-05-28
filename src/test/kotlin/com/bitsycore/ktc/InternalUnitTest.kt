package com.bitsycore.ktc

import com.bitsycore.ktc.codegen.CCodeGen
import com.bitsycore.ktc.codegen.generate
import com.bitsycore.ktc.parser.Lexer
import com.bitsycore.ktc.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class InternalUnitTest : TranspilerTestBase() {

    /* Build two source files in different packages and transpile, returning any
    codegen error message (or null on success). The second file is the "primary"
    output; the first is in allAsts to provide cross-file context. */
    private fun transpileTwoFiles(srcA: String, srcB: String): String? {
        val a = Parser(Lexer(srcA).tokenize()).parseFile()
        val b = Parser(Lexer(srcB).tokenize()).parseFile()
        val gen = CCodeGen(b, listOf(a, b), srcB.lines())
        return try {
            gen.generate()
            null
        } catch (e: Exception) {
            e.message
        }
    }

    @Test fun internalKeywordParses() {
        val r = transpile("""
            package test.Main
            internal class Foo(val x: Int)
            internal fun bar(): Int = 5
            internal val baz: Int = 7
            fun main(args: Array<String>) { val f = Foo(1); println(f.x + bar() + baz) }
        """)
        r.sourceContains("Foo_primaryConstructor")
        r.sourceContains("test_Main_bar")
    }

    @Test fun internalClassSamePkgOk() {
        val msg = transpileTwoFiles(
            "package foo\ninternal class Secret(val v: Int)\n",
            "package foo\nfun main() { val s = Secret(1); println(s.v) }\n"
        )
        assertEquals(null, msg, "same-package internal access should succeed, got: $msg")
    }

    @Test fun internalClassCrossPkgError() {
        val msg = transpileTwoFiles(
            "package foo\ninternal class Secret(val v: Int)\n",
            "package bar\nimport foo.Secret\nfun main() { val s = Secret(1); println(s.v) }\n"
        )
        assertTrue(msg?.contains("E044") == true, "expected E044 on cross-pkg internal access, got: $msg")
        assertTrue(msg.contains("Secret"), "error should name 'Secret', got: $msg")
    }

    @Test fun internalFunCrossPkgError() {
        val msg = transpileTwoFiles(
            "package foo\ninternal fun secretFn(): Int = 42\n",
            "package bar\nimport foo.secretFn\nfun main() { println(secretFn()) }\n"
        )
        assertTrue(msg?.contains("E044") == true, "expected E044 on cross-pkg internal fn call, got: $msg")
        assertTrue(msg.contains("secretFn"), "error should name 'secretFn', got: $msg")
    }

    @Test fun internalFunSamePkgOk() {
        val msg = transpileTwoFiles(
            "package foo\ninternal fun secretFn(): Int = 42\n",
            "package foo\nfun main() { println(secretFn()) }\n"
        )
        assertEquals(null, msg, "same-package internal fn call should succeed, got: $msg")
    }

    @Test fun internalAndPrivateMutuallyExclusive() {
        try {
            transpile("""
                package test.Main
                private internal class Foo
                fun main(args: Array<String>) {}
            """)
            fail("expected parser error on 'private internal'")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("mutually exclusive") == true,
                "expected 'mutually exclusive' diagnostic, got: ${e.message}"
            )
        }
    }
}
