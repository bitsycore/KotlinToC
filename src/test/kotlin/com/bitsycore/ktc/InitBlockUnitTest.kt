package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for `init { }` blocks in class bodies.
 *
 * Init blocks are emitted into the primary constructor, after all property
 * initializers, with $self (a value) and constructor params in scope.
 */
class InitBlockUnitTest : TranspilerTestBase() {

    @Test fun singleInitBlock() {
        val r = transpileMain("val p = Player(\"Alice\")", decls = """
            class Player(val name: String) {
                var health: Int = 0
                init { health = 100 }
            }
        """)
        r.sourceContains("\$self.health = 100;")
    }

    @Test fun multipleInitBlocks() {
        val r = transpileMain("val p = Player(0)", decls = """
            class Player(val start: Int) {
                var x: Int = 0
                var y: Int = 0
                init { x = start }
                init { y = start + 1 }
            }
        """)
        r.sourceContains("\$self.x = start")
        r.sourceContains("\$self.y = (start + 1)")
    }

    @Test fun initBlockWithExpression() {
        val r = transpileMain("val p = Player(\"hello\")", decls = """
            class Player(val name: String) {
                var len: Int = 0
                init { len = name.length }
            }
        """)
        r.sourceContains("\$self.len = name.len")
    }

    @Test fun initBlockBeforeBodyPropsExecutesFirst() {
        val r = transpileMain("val p = Player()", decls = """
            class Player {
                var order: String = ""
                init { order = "init" }
            }
        """)
        // Init should execute after body prop defaults per Kotlin semantics
    }
}
