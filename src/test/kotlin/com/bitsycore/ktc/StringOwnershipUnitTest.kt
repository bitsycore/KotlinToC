package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for the String ownership API — String behaves like a read-only Array:
 * copy() duplicates into the caller's frame; asRef()/copyWith()/allocWith() (S1b)
 * produce the Ref<String> heap/escape form. All produce NUL-terminated buffers.
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
}
