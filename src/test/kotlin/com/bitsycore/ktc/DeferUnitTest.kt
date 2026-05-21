package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for defer statement: basic, block, LIFO ordering, with return values.
 */
class DeferUnitTest : TranspilerTestBase() {

    // ── Basic defer ──────────────────────────────────────────────────

    @Test fun basicDefer() {
        val r = transpile("""
            package test.Main
            fun test() {
                defer println("deferred")
                println("body")
            }
            fun main(args: Array<String>) { test() }
        """)
        // Deferred code should appear after "body" but before function end
        val bodyIdx = r.source.indexOf("\"body\"")
        val deferIdx = r.source.indexOf("\"deferred\"")
        assert(bodyIdx < deferIdx) { "Deferred statement should come after body" }
    }

    // ── Block defer ──────────────────────────────────────────────────

    @Test fun blockDefer() {
        val r = transpile("""
            package test.Main
            fun test() {
                defer {
                    println("A")
                    println("B")
                }
                println("body")
            }
            fun main(args: Array<String>) { test() }
        """)
        r.sourceContains("\"A\"")
        r.sourceContains("\"B\"")
    }

    // ── Defer with return value ──────────────────────────────────────

    @Test fun deferWithReturn() {
        val decls = "data class Vec2(var x: Float, var y: Float)"
        val r = transpile("""
            package test.Main
            $decls
            fun deferredReturn(): Int {
                val p = HeapAlloc<Vec2>(1.0f, 2.0f)!!
                defer HeapFree(p)
                p.x = 42.0f
                return p.x.toInt()
            }
            fun main(args: Array<String>) {
                println(deferredReturn())
            }
        """)
        // Return value should be evaluated into a temp before defers run
        r.sourceMatches(Regex("ktc_Int \\$\\d+ = "))
        r.sourceContains("free(p)")
    }

    // ── Multiple defers (LIFO) ───────────────────────────────────────

    @Test fun multipleDeferLIFO() {
        val r = transpile("""
            package test.Main
            fun test() {
                defer println("first")
                defer println("second")
                println("body")
            }
            fun main(args: Array<String>) { test() }
        """)
        // LIFO: "second" deferred runs before "first"
        val firstIdx = r.source.indexOf("\"first\"", r.source.indexOf("\"body\""))
        val secondIdx = r.source.indexOf("\"second\"", r.source.indexOf("\"body\""))
        assert(secondIdx < firstIdx) { "LIFO: second deferred should execute before first" }
    }
}
