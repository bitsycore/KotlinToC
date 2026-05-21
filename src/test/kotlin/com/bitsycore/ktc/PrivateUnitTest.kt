package com.bitsycore.ktc

import kotlin.test.Test

class PrivateUnitTest : TranspilerTestBase() {

    // ── Private field ────────────────────────────────────────────

    @Test fun privateFieldHasPRIVPrefixInStruct() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                private var health: Int = 100
            }
            fun main(args: Array<String>) {}
        """)
        r.headerContains("PRIV_health")
    }

    @Test fun privateFieldAccessInsideMethod() {
        val r = transpileMain("""
            val p = Player("Alice")
            p.takeDamage(30)
            println(p.getHealth())
        """, decls = """
            class Player(val name: String) {
                private var health: Int = 100
                fun takeDamage(dmg: Int) { health = health - dmg }
                fun getHealth(): Int = health
            }
        """)
        r.sourceContains("PRIV_health")
    }

    // ── Private method ───────────────────────────────────────────

    @Test fun privateMethodHasPRIVPrefix() {
        val r = transpile("""
            package test.Main
            class Calculator {
                private fun secret(): Int = 42
                fun compute(): Int = secret()
            }
            fun main(args: Array<String>) {}
        """)
        r.sourceContains("PRIV_secret")
    }

    @Test fun privateMethodForwardDecl() {
        val r = transpile("""
            package test.Main
            class Calculator {
                fun compute(): Int = secret()
                private fun secret(): Int = 42
            }
            fun main(args: Array<String>) {}
        """)
        r.sourceContains("PRIV_secret")
    }

    @Test fun privateMethodCanCallOtherPrivate() {
        val r = transpileMain("""
            val c = Calculator()
            println(c.compute())
        """, decls = """
            class Calculator {
                fun compute(): Int = add(10, 20)
                private fun add(a: Int, b: Int): Int = a + b
            }
        """)
        r.sourceContains("PRIV_add")
    }

    // ── Data class private field ─────────────────────────────────

    @Test fun dataClassPrivateFieldInEquals() {
        val r = transpile("""
            package test.Main
            data class User(val name: String) {
                private var id: Int = 0
            }
            fun main(args: Array<String>) {}
        """)
        // Equals uses PRIV_ prefix for private fields
        r.sourceContains("PRIV_id")
        r.headerContains("PRIV_id")
    }

    @Test fun dataClassPrivateFieldInToString() {
        val r = transpile("""
            package test.Main
            data class User(val name: String) {
                private var id: Int = 0
            }
            fun main(args: Array<String>) {}
        """)
        // toString accesses the private field with PRIV_ prefix
        r.sourceContains("PRIV_id")
    }

    // ── Multiple private fields and methods ──────────────────────

    @Test fun multiplePrivateFields() {
        val r = transpileMain("""
            val e = Entity()
            println(e.status())
        """, decls = """
            class Entity {
                private var x: Int = 10
                private var y: Int = 20
                fun status(): Int = x + y
            }
        """)
        r.sourceContains("PRIV_x")
        r.sourceContains("PRIV_y")
    }

    @Test fun privateFieldDefaultValue() {
        val r = transpile("""
            package test.Main
            class Counter {
                private var count: Int = 42
                fun value(): Int = count
            }
            fun main(args: Array<String>) {}
        """)
        r.sourceContains("PRIV_count")
    }

    // ── Visibility error: access private from outside ─────────────

    @Test fun privateObjectFieldAccessedFromOutsideError() {
        transpileExpectError("""
            package test.Main
            object Config {
                private val secret: Int = 42
            }
            fun main(args: Array<String>) {
                println(Config.secret)
            }
        """.trimIndent(), "Cannot access 'secret': it is private in object 'Config'")
    }

    @Test fun privateObjectFieldAccessedFromOutsideCompanionError() {
        transpileExpectError("""
            package test.Main
            class Foo {
                companion object {
                    private val key: Int = 99
                }
            }
            fun main(args: Array<String>) {
                println(Foo.key)
            }
        """.trimIndent(), "Cannot access 'key': it is private in object 'Foo.Companion'")
    }

    @Test fun privateObjectFieldAccessedFromInsideIsOk() {
        val r = transpile("""
            package test.Main
            object Config {
                private val secret: Int = 42
                fun getSecret(): Int = secret
            }
            fun main(args: Array<String>) {
                println(Config.getSecret())
            }
        """)
        r.sourceContains("PRIV_secret")
    }
}
