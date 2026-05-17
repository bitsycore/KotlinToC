package com.bitsycore.ktc

import kotlin.test.Test

/**
 * Tests for classes with body properties, methods, constructors.
 */
class ClassUnitTest : TranspilerTestBase() {

    // ── Class with ctor params and body props ────────────────────────

    @Test fun classWithBodyProps() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
                var score: Int = 0
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                println(p.health)
            }
        """)
        r.headerContains("ktc_String name;")
        r.headerContains("ktc_Int health;")
        r.headerContains("ktc_Int score;")
    }

    @Test fun classConstructorInit() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
            }
        """)
        // Constructor should set ctor params + body prop defaults
        r.sourceContains("test_Main_Player_primaryConstructor")
        r.sourceContains("health = 100")
    }

    // ── Class methods ────────────────────────────────────────────────

    @Test fun classMethod() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
                fun takeDamage(amount: Int) {
                    health -= amount
                }
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                p.takeDamage(30)
            }
        """)
        r.sourceContains("void test_Main_Player_takeDamage(test_Main_Player* ${'$'}self, ktc_Int amount)")
    }

    @Test fun classMethodExprBody() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
                fun isAlive(): Boolean = health > 0
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                println(p.isAlive())
            }
        """)
        r.sourceContains("ktc_Bool test_Main_Player_isAlive(test_Main_Player* ${'$'}self)")
    }

    // ── Class with only ctor params (var) ────────────────────────────

    @Test fun classVarCtorParam() {
        val r = transpile("""
            package test.Main
            class Counter(var count: Int) {
                fun increment() { count++ }
                fun get(): Int = count
            }
            fun main(args: Array<String>) {
                val c = Counter(0)
                c.increment()
                println(c.get())
            }
        """)
        r.headerContains("ktc_Int count;")
        r.sourceContains("test_Main_Counter_increment")
        r.sourceContains("test_Main_Counter_get")
    }

    // ── Property access via dot ──────────────────────────────────────

    @Test fun propertyRead() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                println(p.health)
            }
        """)
        r.sourceContains("p.health")
    }

    @Test fun propertyWrite() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                p.health = 50
            }
        """)
        r.sourceContains("p.health = 50;")
    }

    // ── Nested / inner classes ─────────────────────────────────────

    @Test fun `nested class struct emitted with dollar separator`() {
        val r = transpile("""
            package test.Main
            class Outer {
                class Inner(val x: Int)
            }
            fun main() { }
        """)
        r.headerContains("KTC_TYPE(CLS, CLS_OPT,")
        r.headerContains("Outer\$Inner")
        r.headerContains("ktc_Int x;")
    }

    @Test fun `nested class constructor call resolved`() {
        val r = transpile("""
            package test.Main
            class Outer {
                class Inner(val x: Int)
            }
            fun main() {
                val inner = Outer.Inner(42)
            }
        """)
        r.sourceContains("Outer\$Inner_primaryConstructor(42)")
    }

    @Test fun `nested class in data class`() {
        val r = transpile("""
            package test.Main
            data class Outer(val a: Int) {
                class Inner(val b: Float)
            }
            fun main() {
                val i = Outer.Inner(3f)
            }
        """)
        r.headerContains("Outer\$Inner")
        r.sourceContains("Outer\$Inner_primaryConstructor(3.0f)")
    }

    @Test fun `nested class field access`() {
        val r = transpile("""
            package test.Main
            class Outer {
                class Inner(val name: String)
            }
            fun main() {
                val i = Outer.Inner("test")
                println(i.name)
            }
        """)
        r.sourceContains("i.name")
    }

    @Test fun `deeply nested class`() {
        val r = transpile("""
            package test.Main
            class A {
                class B {
                    class C(val v: Int)
                }
            }
            fun main() {
                val c = A.B.C(1)
            }
        """)
        r.headerContains("A\$B\$C")
        r.sourceContains("A\$B\$C_primaryConstructor(1)")
    }

    @Test fun `nested class type annotation`() {
        val r = transpile("""
            package test.Main
            class Outer {
                class Inner(val x: Int)
            }
            fun make(): Outer.Inner = Outer.Inner(42)
        """)
        r.sourceContains("Outer\$Inner_primaryConstructor(42)")
    }
}
