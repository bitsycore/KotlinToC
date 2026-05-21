package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for object declarations (singletons).
 */
class ObjectUnitTest : TranspilerTestBase() {

    private val configDecl = """
        object Config {
            val maxRetries: Int = 3
            var debug: Boolean = false
        }
    """

    // ── Object struct ────────────────────────────────────────────────

    @Test fun objectStructTypedef() {
        val r = transpileMain("println(Config.maxRetries)", decls = configDecl)
        r.headerContains("KTC_OBJECT(")                       // macro-based struct + extern
        r.headerContains("#define KTC_TYPE_NAME test_Main_Config")      // KTC_TYPE_NAME define before macro
        r.headerContains("mangled: test_Main_Config")         // banner shows mangled C name
    }

    // ── Object field access ──────────────────────────────────────────

    @Test fun objectFieldRead() {
        val r = transpileMain("println(Config.maxRetries)", decls = configDecl)
        r.sourceContains("test_Main_Config.maxRetries")
    }

    @Test fun objectFieldWrite() {
        val r = transpileMain("Config.debug = true", decls = configDecl)
        r.sourceContains("test_Main_Config.debug = true;")
    }

    // ── Object initialization ────────────────────────────────────────

    @Test fun objectInitialization() {
        val r = transpileMain("println(Config.maxRetries)", decls = configDecl)
        r.sourceContains("test_Main_Config_t test_Main_Config = {")
    }

    // ── Object not confused with nullable ────────────────────────────

    @Test fun objectAccessNotNullError() {
        // Object access via dot should NOT trigger nullable receiver error
        val r = transpileMain("Config.debug = true", decls = configDecl)
        r.sourceContains("test_Main_Config.debug")
    }

    // ── Object Any inheritance ────────────────────────────────────────

    @Test fun objectHasBaseField() {
        val r = transpileMain("Config.maxRetries", decls = configDecl)
        r.headerContains("ktc_core_AnyData __base;")
    }

    @Test fun objectBaseTypeIdInit() {
        val r = transpileMain("Config.maxRetries", decls = configDecl)
        r.sourceContains("test_Main_Config.__base.typeId = test_Main_Config_TYPE_ID;")
    }

    @Test fun objectHasToString() {
        val r = transpileMain("Config.maxRetries", decls = configDecl)
        r.headerContains("KTC_METHOD(void, toString)(test_Main_Config_t* \$self, ktc_StrBuf* sb);")
        r.sourceContains("void test_Main_Config_toString(test_Main_Config_t* \$self, ktc_StrBuf* sb)")
    }

    @Test fun objectHasHashCode() {
        val r = transpileMain("Config.maxRetries", decls = configDecl)
        r.headerContains("KTC_METHOD(ktc_Int, hashCode)(void);")
        r.sourceContains("void test_Main_Config_toString(test_Main_Config_t* \$self, ktc_StrBuf* sb)")
    }

    @Test fun objectAnyVtable() {
        val r = transpileMain("Config.maxRetries", decls = configDecl)
        r.sourceContains("const ktc_core_AnyVt test_Main_Config_AnyVt = {")
    }

    @Test fun objectAsAny() {
        val r = transpileMain("Config.maxRetries", decls = configDecl)
        r.headerContains("ktc_Any test_Main_Config_as_Any(test_Main_Config_t*")
    }

    @Test fun objectAnyWrappersDelegate() {
        val r = transpileMain("Config.maxRetries", decls = configDecl)
        r.sourceContains("test_Main_Config_toString((test_Main_Config_t*)\$self, sb);")
        r.sourceContains("return test_Main_Config_hashCode();")
    }

    // ── Object with override fun dispose ──────────────────────────────

    private val objWithDispose = """
        object Res {
            var released = false
            override fun dispose() { released = true }
        }
    """

    @Test fun objectDisposeOverrideAnyWrapperDelegates() {
        val r = transpileMain("Res.dispose()", decls = objWithDispose)
        // dispose_any should call the object's dispose method
        r.sourceContains("test_Main_Res_dispose();")
    }

    // ── Object with override fun toString ─────────────────────────────

    private val objWithToString = """
        object Label {
            override fun toString(): String = "Label"
        }
    """

    @Test fun objectToStringOverrideDoesNotAutogen() {
        val r = transpileMain("Label.toString()", decls = objWithToString)
        // Should NOT have auto-generated toString in "implements Any" section
        // (the user's override is in the "methods" section with different prefix)
        val afterImplementsAny = r.source.indexOf("implements Any")
        val toStringComment = r.source.indexOf("// ══ fun toString() ══", afterImplementsAny)
        if (toStringComment >= 0) error("auto-generated toString found in implements Any section")
    }

    @Test fun objectToStringOverrideAnyWrapperDelegates() {
        val r = transpileMain("Label.toString()", decls = objWithToString)
        // toString_any should call the object's toString and append result to buffer
        r.sourceContains("ktc_String s = test_Main_Label_toString();")
        r.sourceContains("ktc_core_sb_append_str(sb, s);")
    }
}
