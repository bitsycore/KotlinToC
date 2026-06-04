package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for the String ownership API — String behaves like a read-only Array:
 * copy() duplicates into the caller's frame; asRef() yields a Ref<String> alias;
 * copyWith()/allocWith() heap-promote to a returnable Ref<String>. Ref<String> is
 * a ktc_String value (ptr+len) exactly like Ref<Array<T>> is a VarArr — the Ref<>
 * is a compile-time marker, and freeMem(ref) releases ref.ptr. All buffers are NUL-terminated.
 */
class StringOwnershipUnitTest : TranspilerTestBase() {

    // s.copy() allocas a fresh frame buffer, memcpy's the bytes and NUL-terminates
    // via ktc_core_string_copy — an owned, independent String (no alias of the source).
    @Test fun copyAllocatesOwnedBuffer() {
        val r = transpileMain(
            $$"""
            val s = "hello"
            val c = s.copy()
            println(c)
            """
        )
        r.sourceContains("ktc_core_alloca(")
        r.sourceContains("ktc_core_string_copy(")
    }

    // s.asRef() takes the address of this String → a Ref<String> (ktc_String*); no copy.
    @Test fun asRefTakesAddress() {
        val r = transpileMain(
            $$"""
            val s = "hello"
            val r = s.asRef()
            println(s)
            """
        )
        r.sourceContains("ktc_String* r = &(s)")
        r.sourceNotContains("ktc_core_string_copy")   // asRef does not copy
    }

    // s.copyWith(Heap) allocates one heap block (ktc_String header + NUL-terminated bytes inline) and
    // yields a Ref<String> (ktc_String*). A function may return it (escapes the frame); freeMem frees the block.
    @Test fun copyWithHeapReturnsRefString() {
        val r = transpileMainWithStdlib(
            body = "val h = mk()\nHeap.freeMem(h)",
            decls = $$"""
                fun mk(): Ref<String> {
                    val s = "hello"
                    return s.copyWith(Heap)
                }
            """
        )
        r.sourceContains("sizeof(ktc_String)")   // header + bytes in one block
        r.sourceContains("malloc(")
        r.sourceContains("memcpy(")
        r.sourceContains("ktc_Heap_freeMem(")
    }

    // allocWith is the move-to-heap alias of copyWith for an existing String value (literal receiver here).
    @Test fun allocWithHeapAllocates() {
        val r = transpileMainWithStdlib(
            body = "Heap.freeMem(mk())",
            decls = "fun mk(): Ref<String> = \"hello world\".allocWith(Heap)"
        )
        r.sourceContains("malloc(")
    }

    // Returning s.asRef() of a frame String dangles — refused by E120 (heap form is .allocWith/.copyWith).
    @Test fun returnAsRefOfFrameStringRefused() {
        transpileExpectError(
            $$"""
            package test
            fun bad(): Ref<String> {
                val s = "x"
                return s.asRef()
            }
            """,
            "E120"
        )
    }

    // .cPtr is the raw C pointer accessor (RawArray<Char> / T*); it lowers to the struct's .ptr field.
    @Test fun cPtrLowersToRawPointer() {
        val r = transpileMain(
            $$"""
            val s = "hi"
            val p = s.cPtr
            println(s)
            """
        )
        r.sourceContains("ktc_Char* p")
    }

    // .ptr is no longer allowed on String — must use .cPtr (E055).
    @Test fun ptrOnStringRefused() {
        transpileMainExpectError("val s = \"hi\"\nval p = s.cPtr\nval q = s.ptr", "E055")
    }

    // .ptr is no longer allowed on Array either — must use .cPtr (E055).
    @Test fun ptrOnArrayRefused() {
        transpileMainExpectError("val a = arrayOf(1, 2)\nval p = a.ptr", "E055")
    }

    // S3: a string literal used 2+ times is interned into a named, read-only static .rodata array.
    @Test fun repeatedLiteralInternedToStaticPool() {
        val r = transpileMain(
            $$"""
            val a = "shared pool text"
            val b = "shared pool text"
            println(a)
            println(b)
            """
        )
        r.headerContains("static const ktc_Char ktc_str_")
    }
}
