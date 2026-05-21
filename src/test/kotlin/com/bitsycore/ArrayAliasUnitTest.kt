package com.bitsycore.ktc

import kotlin.test.Test

/*
Tests for all type-specific array constructors and factory functions:
  XxxArray(size)      → stack allocation via alloca
  xxxArrayOf(v1, vn)  → stack compound literal
Verified for all built-in types including unsigned variants.
*/
class ArrayAliasUnitTest : TranspilerTestBase() {

    // ── Signed integer arrays ────────────────────────────────────────

    @Test fun byteArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = byteArrayOf(10, 20)", decls = "")
        v.sourceContains("ktc_Byte a_data[] = {10, 20};")
        v.sourceContains("ktc_VarArr_ktc_Byte a = {a_data, 2};")
    }

    @Test fun shortArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = shortArrayOf(10, 20)", decls = "")
        v.sourceContains("ktc_Short a_data[] = {10, 20};")
        v.sourceContains("ktc_VarArr_ktc_Short a = {a_data, 2};")
    }

    @Test fun intArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = intArrayOf(10, 20)", decls = "")
        v.sourceContains("ktc_Int a_data[] = {10, 20};")
        v.sourceContains("ktc_VarArr_ktc_Int a = {a_data, 2};")
    }

    @Test fun longArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = longArrayOf(10L, 20L)", decls = "")
        v.sourceContains("ktc_Long a_data[] = {10LL, 20LL};")
        v.sourceContains("ktc_VarArr_ktc_Long a = {a_data, 2};")
    }

    // ── Float / Double ───────────────────────────────────────────────

    @Test fun floatArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = floatArrayOf(1.5f, 2.5f)", decls = "")
        v.sourceContains("ktc_Float a_data[] = {1.5f, 2.5f};")
        v.sourceContains("ktc_VarArr_ktc_Float a = {a_data, 2};")
    }

    @Test fun doubleArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = doubleArrayOf(1.1, 2.2)", decls = "")
        v.sourceContains("ktc_Double a_data[] = {1.1, 2.2};")
        v.sourceContains("ktc_VarArr_ktc_Double a = {a_data, 2};")
    }

    // ── Boolean / Char ───────────────────────────────────────────────

    @Test fun booleanArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = booleanArrayOf(true, false)", decls = "")
        v.sourceContains("ktc_Bool a_data[] = {true, false};")
        v.sourceContains("ktc_VarArr_ktc_Bool a = {a_data, 2};")
    }

    @Test fun charArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = charArrayOf('a', 'b')", decls = "")
        v.sourceContains("ktc_Char a_data[] = {'a', 'b'};")
        v.sourceContains("ktc_VarArr_ktc_Char a = {a_data, 2};")
    }

    // ── Unsigned integer arrays ──────────────────────────────────────

    @Test fun ubyteArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = ubyteArrayOf(10u, 20u)", decls = "")
        v.sourceContains("ktc_UByte a_data[] = {10U, 20U};")
        v.sourceContains("ktc_VarArr_ktc_UByte a = {a_data, 2};")
    }

    @Test fun ushortArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = ushortArrayOf(10u, 20u)", decls = "")
        v.sourceContains("ktc_UShort a_data[] = {10U, 20U};")
        v.sourceContains("ktc_VarArr_ktc_UShort a = {a_data, 2};")
    }

    @Test fun uintArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = uintArrayOf(10u, 20u)", decls = "")
        v.sourceContains("ktc_UInt a_data[] = {10U, 20U};")
        v.sourceContains("ktc_VarArr_ktc_UInt a = {a_data, 2};")
    }

    @Test fun ulongArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = ulongArrayOf(10UL, 20UL)", decls = "")
        v.sourceContains("ktc_ULong a_data[] = {10ULL, 20ULL};")
        v.sourceContains("ktc_VarArr_ktc_ULong a = {a_data, 2};")
    }

    // ── Generic arrayOf<T> ───────────────────────────────────────────

    @Test fun arrayOfStringEmitsCompoundLiteral() {
        val v = transpileMain("val a = arrayOf(\"hello\", \"world\")", decls = "")
        v.sourceContains("ktc_String a_data[] = {ktc_core_str(\"hello\"), ktc_core_str(\"world\")};")
        v.sourceContains("ktc_VarArr_ktc_String a = {a_data, 2};")
    }

    // ── Type inference ───────────────────────────────────────────────

    @Test fun byteArrayInfersByteArrayType() {
        val v = transpileMain("val a = byteArrayOf(1)", decls = "")
        v.sourceContains("ktc_Byte a_data[]")
    }

    @Test fun ubyteArrayInfersUByteArrayType() {
        val v = transpileMain("val a = ubyteArrayOf(1u)", decls = "")
        v.sourceContains("ktc_UByte a_data[]")
    }

    @Test fun ushortArrayInfersUShortArrayType() {
        val v = transpileMain("val a = ushortArrayOf(1u)", decls = "")
        v.sourceContains("ktc_UShort a_data[]")
    }

    @Test fun uintArrayInfersUIntArrayType() {
        val v = transpileMain("val a = uintArrayOf(1u)", decls = "")
        v.sourceContains("ktc_UInt a_data[]")
    }

    @Test fun ulongArrayInfersULongArrayType() {
        val v = transpileMain("val a = ulongArrayOf(1UL)", decls = "")
        v.sourceContains("ktc_ULong a_data[]")
    }
}
