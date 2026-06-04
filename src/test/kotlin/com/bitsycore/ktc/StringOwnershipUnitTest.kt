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

    // S4: toStringMaxLen() is a compile-time constant; toStringComputeLen() counts via a NULL StrBuf (no alloc).
    @Test fun toStringLenIntrinsics() {
        val r = transpileMain(
            $$"""
            val n = 42
            val mx = n.toStringMaxLen()
            val cl = n.toStringComputeLen()
            println(mx)
            println(cl)
            """
        )
        r.sourceMatches(Regex("ktc_Int mx = [0-9]+"))   // maxLen: compile-time constant
        r.sourceContains("ktc_StrBuf")                  // computeLen: counting buffer
        r.sourceContains("ktc_core_sb_append_int")
    }

    // toStringMaxLen() is refused when the toString() length isn't statically bounded (e.g. String).
    @Test fun toStringMaxLenRefusedForUnbounded() {
        transpileMainExpectError("val s = \"x\"\nval m = s.toStringMaxLen()", "toStringMaxLen")
    }

    // S5: templateOf("…") — a frame-local Template handle (compile-time only). maxLen (static const),
    // computeLen (counting StrBuf), toString (build). The handle emits no C value of its own.
    @Test fun templateOfOperations() {
        val r = transpileMain(
            $$"""
            val n = 7
            val name = "World"
            val tb = templateOf("n=$n")
            val mx = tb.maxLen
            val tu = templateOf("hi $name")
            val cl = tu.computeLen()
            val s = tu.toString()
            println(s)
            println(mx)
            println(cl)
            """
        )
        r.sourceMatches(Regex("ktc_Int mx = [0-9]+"))     // maxLen: compile-time constant
        r.sourceContains("{NULL, 0, 0}")                  // computeLen: counting StrBuf
        r.sourceContains("ktc_core_sb_to_string")         // toString: build
        r.sourceNotContains("__template")                 // the handle's sentinel never reaches emitted C
    }

    // template.maxLen is refused when the template's length isn't statically bounded (a String interpolation).
    @Test fun templateMaxLenRefusedForUnbounded() {
        transpileMainExpectError(
            "val name = \"x\"\nval t = templateOf(\"hi \$name\")\nval m = t.maxLen",
            "not statically bounded"
        )
    }

    // S6: sb."text $x" renders the template into the StringBuffer receiver and returns the rendered String.
    @Test fun sbReceiverTemplate() {
        val r = transpileMain(
            $$"""
            val name = "World"
            val buf = CharArray(64)
            val sb = StringBuffer(buf.asRef(), 0)
            val s = sb."hi $name"
            println(s)
            """
        )
        r.sourceContains("ktc_core_sb_append_str")   // renders into the StringBuffer
        r.sourceContains("ktc_core_sb_to_string")     // returns the rendered String
    }
}
