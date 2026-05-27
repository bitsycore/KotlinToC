package com.bitsycore.ktc

import kotlin.test.Test

/* Unit tests for `typealias Name = TargetType`.
The transpiler resolves aliases by substitution at type-resolution sites,
so the generated C uses the underlying type and the alias name is gone. */
class TypeAliasUnitTest : TranspilerTestBase() {

    // ── Primitive alias ───────────────────────────────────────────────

    @Test fun aliasOfPrimitiveIsErased() {
        val r = transpile("""
            package test.Main
            typealias UserId = Int
            fun main(args: Array<String>) {
                val id: UserId = 42
            }
        """)
        r.sourceContains("ktc_Int id = 42")
        r.sourceNotContains("UserId")
    }

    @Test fun aliasOfPrimitiveInFunctionSignature() {
        val r = transpile("""
            package test.Main
            typealias Score = Int
            fun double(s: Score): Score = s * 2
            fun main(args: Array<String>) { val r = double(21) }
        """)
        // Function parameter and return both use the underlying type.
        r.sourceContains("ktc_Int test_Main_double(ktc_Int s)")
    }

    // ── Alias of a class ──────────────────────────────────────────────

    @Test fun aliasOfDataClass() {
        val r = transpile("""
            package test.Main
            data class Vec2(val x: Float, val y: Float)
            typealias Point = Vec2
            fun main(args: Array<String>) {
                val p: Point = Vec2(1.0f, 2.0f)
            }
        """)
        r.sourceContains("test_Main_Vec2 p =")
    }

    // ── Chained aliases (alias of an alias) ───────────────────────────

    @Test fun chainedAliasResolves() {
        val r = transpile("""
            package test.Main
            typealias A = Int
            typealias B = A
            typealias C = B
            fun main(args: Array<String>) {
                val v: C = 7
            }
        """)
        r.sourceContains("ktc_Int v = 7")
    }

    // ── Cycle detection ───────────────────────────────────────────────

    @Test fun aliasCycleIsRejected() {
        val ex = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            transpile("""
                package test.Main
                typealias A = B
                typealias B = A
                fun main(args: Array<String>) { val x: A = 0 }
            """)
        }
        assert(ex.message!!.contains("typealias cycle")) { "got: ${ex.message}" }
    }

    // ── Alias nullability is forwarded ────────────────────────────────

    @Test fun aliasNullabilityWorks() {
        val r = transpile("""
            package test.Main
            typealias MaybeInt = Int?
            fun main(args: Array<String>) {
                val x: MaybeInt = null
            }
        """)
        // Int? lowers to ktc_Int$Opt.
        r.sourceContains("ktc_Int\$Opt x")
    }
}
