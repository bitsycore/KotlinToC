package com.bitsycore.ktc

import com.bitsycore.ktc.codegen.CCodeGen
import com.bitsycore.ktc.codegen.generate
import com.bitsycore.ktc.parser.Lexer
import com.bitsycore.ktc.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/* B9: a generic constructor whose type argument cannot be inferred (e.g. from `null`) must error
   with E045 rather than silently materializing the wrong monomorphization (T = Int), which would
   emit wrong-size / uncompilable C. Inferable and explicitly-typed constructions still succeed. */
class GenericInferUnitTest : TranspilerTestBase() {

    /* Transpile a single source; return the codegen error message, or null on success. */
    private fun transpileMsg(src: String): String? {
        val vSrc = src.trimIndent()
        val vAst = Parser(Lexer(vSrc).tokenize()).parseFile()
        return try {
            CCodeGen(vAst, listOf(vAst), vSrc.lines()).generate()
            null
        } catch (e: Exception) {
            e.message
        }
    }

    @Test fun genericCtorNullArgErrorsE045() {
        val msg = transpileMsg(
            """
            package test
            class Box<T>(val value: T)
            fun main(): Int { val b = Box(null); return 0 }
            """
        )
        assertTrue(msg?.contains("E045") == true, "expected E045 on uninferable generic ctor arg, got: $msg")
        assertTrue(msg.contains("Box"), "error should name 'Box', got: $msg")
    }

    @Test fun genericCtorInferredArgOk() {
        val msg = transpileMsg(
            """
            package test
            class Box<T>(val value: T)
            fun main(): Int { val b = Box(5); return b.value }
            """
        )
        assertEquals(null, msg, "inferable generic ctor Box(5) should succeed, got: $msg")
    }

    @Test fun genericCtorExplicitTypeArgOk() {
        val msg = transpileMsg(
            """
            package test
            class Box<T>(val value: T)
            fun main(): Int { val b = Box<Int?>(null); return 0 }
            """
        )
        assertEquals(null, msg, "explicit type arg Box<Int?>(null) should succeed, got: $msg")
    }
}
