package com.bitsycore.ktc

import kotlin.test.Test

/*
Unit tests for Sha256 stdlib — verifies transpiler output structure.
*/
class Sha256UnitTest : TranspilerTestBase() {

    @Test fun sha256ObjectEmitted() {
        val v = transpileMain("", """
            object Sha256 {
                private val K: @Size(64) UIntArray = uintArrayOf(0x428a2f98U)
                fun new(): Context = Context()
                class Context() {
                    private val state: @Size(8) UIntArray = uintArrayOf(0x6a09e667U)
                    fun update(buff: @Ptr ByteArray, offset: Int = 0, length: Int = buff.size) {}
                    fun finalizeHash(): @Size(32) ByteArray { return ByteArray(32) }
                }
                private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
            }
        """)
        v.headerContains("KTC_OBJECT(")                       // macro-based struct + extern
        v.headerContains("#define KTC_TYPE_NAME test_Main_Sha256")      // KTC_TYPE_NAME define
        v.sourceContains("test_Main_Sha256_t test_Main_Sha256 = {0};")
    }

    @Test fun sha256StructHasPrivateK() {
        val v = transpileMain("", """
            object Sha { private val K: @Size(4) UIntArray = uintArrayOf(1U, 2U, 3U, 4U) }
        """)
        // PRIV_ prefix on struct field in header
        v.headerContains("PRIV_K")
    }

    @Test fun sha256NewReturnsNestedClass() {
        val v = transpileMain("", """
            object Obj {
                class Inner(val x: Int)
                fun make(): Inner = Inner(42)
            }
        """)
        v.headerContains("KTC_METHOD(test_Main_Obj\$Inner, make)(void)")
    }

    @Test fun sha256ContextClassEmitted() {
        val v = transpileMain("", """
            object Obj {
                class Inner(val x: Int)
            }
        """)
        v.headerContains("#define KTC_TYPE_NAME test_Main_Obj\$Inner")
        v.headerContains("KTC_METHOD(KTC_TYPE_NAME, primaryConstructor)")
    }

    @Test fun sha256PrivateMethodHasPRIV() {
        val v = transpileMain("", """
            object Obj {
                private fun helper(): Int = 0
                fun call(): Int = helper()
            }
        """)
        v.sourceContains("test_Main_Obj_PRIV_helper")
    }

    @Test fun sha256PrivateMethodForwardDeclared() {
        val v = transpileMain("", """
            object Obj {
                private fun helper(): Int = 0
                fun call(): Int = helper()
            }
        """)
        val src = v.source ?: ""
        val fwdIdx = src.indexOf("test_Main_Obj_PRIV_helper();")
        val defIdx = src.indexOf("test_Main_Obj_PRIV_helper() {")
        kotlin.test.assertTrue(fwdIdx >= 0, "Forward decl not found")
        kotlin.test.assertTrue(defIdx >= 0, "Definition not found")
        kotlin.test.assertTrue(fwdIdx < defIdx, "Forward decl must come before definition")
    }

    @Test fun sha256NestedClassPrivateFieldPRIVPrefix() {
        val v = transpileMain("", """
            object Obj {
                class Inner() {
                    private val buf: @Size(8) ByteArray = ByteArray(8)
                    private var count: Int = 0
                }
            }
        """)
        // PRIV_ prefix on struct fields in header
        v.headerContains("PRIV_buf")
        v.headerContains("PRIV_count")
    }

    @Test fun sha256ContextUpdateHasLenParam() {
        val v = transpileMain("", """
            object Obj {
                class Inner() {
                    fun update(buff: @Ptr ByteArray, offset: Int = 0, length: Int = buff.size) {}
                }
            }
        """)
        v.headerContains("ktc_Int buff\$len")
    }

    @Test fun sha256FinalizeHashOutParam() {
        val v = transpileMain("", """
            object Obj {
                class Inner() {
                    fun finalizeHash(): @Size(32) ByteArray { return ByteArray(32) }
                }
            }
        """)
        v.headerContains("KTC_METHOD(void, finalizeHash)(KTC_TYPE_NAME* \$self, ktc_Byte* \$out)")
    }

    @Test fun sha256InitFlagStatic() {
        val v = transpileMain("", """
            object Sha { val x = 1 }
        """)
        v.sourceContains("static ktc_thread_once_t test_Main_Sha\$once = KTC_THREAD_ONCE_INIT;")
    }

    @Test fun sha256EnsureInitCalledInMethods() {
        val v = transpileMain("", """
            object Sha {
                var x = 0
                fun getX(): Int = x
            }
        """)
        v.sourceContains("test_Main_Sha_\$ensure_init()")
    }

    @Test fun sha256InvOperatorWorks() {
        val v = transpileMain("val r = 42.inv()", "")
        v.sourceContains("(~(42))")
    }

    @Test fun sha256HexLiteralULongWorks() {
        val v = transpileMain("val x: ULong = 0x853c49e6748fea9bUL", "")
        v.sourceContains("0x853c49e6748fea9bULL")
    }
}
