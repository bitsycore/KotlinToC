package com.bitsycore.ktc

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for generic class monomorphization.
 *
 * Generic classes like `class MyList<T>(...)` are compiled via monomorphization:
 * each unique instantiation (e.g. MyList<Int>, MyList<Float>) generates a concrete
 * C struct and functions with mangled names (MyList_Int, MyList_Float).
 */
class GenericsUnitTest : TranspilerTestBase() {

    // ── Basic generic class declaration + instantiation ──────────────

    @Test fun genericClassStructEmitted() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
            }
        """)
        // Mangled struct: Box_Int (forward typedef + struct definition using CLS macro)
        r.headerContains("typedef struct test_Main_Box_Int test_Main_Box_Int;")
        r.headerContains("KTC_CLASS(CLS, CLS_OPT,")
        r.headerContains("ktc_Int item;")
    }

    @Test fun genericClassCreateFunction() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
            }
        """)
        // Constructor function with mangled name
        r.sourceContains("test_Main_Box_Int_primaryConstructor(")
        r.sourceContains("test_Main_Box_Int_primaryConstructor(42)")
    }

    @Test fun genericClassHeapConstructors() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
            }
        """)
        // Heap new is inlined (no separate _new function)
        r.headerContains("KTC_METHOD(CLS, primaryConstructor)(")
    }

    @Test fun genericTemplateNotEmitted() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
            }
        """)
        // The generic template itself should NOT appear as a concrete struct
        r.sourceNotContains("test_Main_Box_primaryConstructor(")
        r.headerMatches(Regex("test_Main_Box\\_Int"))
    }

    // ── Multiple instantiations ─────────────────────────────────────

    @Test fun multipleInstantiations() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val a = Box<Int>(42)
                val b = Box<Float>(3.14f)
            }
        """)
        // Both concrete types emitted
        r.headerContains("test_Main_Box_Int")
        r.headerContains("test_Main_Box_Float")
        r.sourceContains("test_Main_Box_Int_primaryConstructor(42)")
        r.sourceContains("test_Main_Box_Float_primaryConstructor(3.14f)")
    }

    @Test fun stringInstantiation() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val b = Box<String>("hello")
            }
        """)
        r.headerContains("test_Main_Box_String")
        r.headerContains("ktc_String item;")
    }

    // ── Generic class with methods ──────────────────────────────────

    @Test fun genericClassMethodEmitted() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T) {
                fun get(): T = item
            }
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
                println(b.get())
            }
        """)
        r.sourceContains("test_Main_Box_Int_get(")
        // Method return type is concrete ktc_Int
        r.sourceMatches(Regex("ktc_Int test_Main_Box\\_Int_get"))
    }

    @Test fun genericClassMethodWithParam() {
        val r = transpile("""
            package test.Main
            class Box<T>(var item: T) {
                fun set(newItem: T) {
                    item = newItem
                }
            }
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
                b.set(99)
            }
        """)
        r.sourceContains("test_Main_Box_Int_set(")
        // Method takes concrete ktc_Int parameter
        r.sourceMatches(Regex("void test_Main_Box\\_Int_set.*ktc_Int"))
    }

    // ── Generic class with body props ───────────────────────────────

    @Test fun genericClassBodyProp() {
        val r = transpile("""
            package test.Main
            class Wrapper<T>(val item: T) {
                var count: Int = 0
            }
            fun main(args: Array<String>) {
                val w = Wrapper<Int>(42)
            }
        """)
        r.headerContains("ktc_Int item;")
        r.headerContains("ktc_Int count;")
        r.sourceContains("count = 0")
    }

    // ── HeapAlloc with generic class ───────────────────────────────────

    @Test fun heapAllocGenericClass() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val b = HeapAlloc<Box<Int>>(42)
            }
        """)
        // HeapAlloc<Box<Int>>(42) → inline malloc + primaryConstructor
        r.sourceContains("test_Main_Box_Int_primaryConstructor(42)")
        r.sourceContains("malloc(sizeof(test_Main_Box_Int))")
    }

    // ── Generic class with multiple ctor params ─────────────────────

    @Test fun genericClassMultipleCtorParams() {
        val r = transpile("""
            package test.Main
            class Pair<T>(val first: T, val second: T)
            fun main(args: Array<String>) {
                val p = Pair<Int>(1, 2)
            }
        """)
        r.headerContains("ktc_Int first;")
        r.headerContains("ktc_Int second;")
        r.sourceContains("test_Main_Pair_Int_primaryConstructor(1, 2)")
    }

    // ── VarDecl with generic class type ─────────────────────────────

    @Test fun varDeclWithGenericType() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
            }
        """)
        // Local variable should use the mangled type
        r.sourceContains("test_Main_Box_Int b = test_Main_Box_Int_primaryConstructor(42);")
    }

    // ── Generic class with method that calls self fields ────────────

    @Test fun genericMethodAccessesSelfField() {
        val r = transpile("""
            package test.Main
            class Container<T>(var stored: T) {
                fun swap(newVal: T): T {
                    val old = stored
                    stored = newVal
                    return old
                }
            }
            fun main(args: Array<String>) {
                val c = Container<Int>(10)
                val old = c.swap(20)
            }
        """)
        r.sourceContains("test_Main_Container_Int_swap(")
        // Method body accesses $self->stored
        r.sourceMatches(Regex("\\${'$'}self->stored"))
    }

    // ── Type substitution in body prop types ────────────────────────

    @Test fun genericBodyPropWithTypeParam() {
        val r = transpile("""
            package test.Main
            class Stack<T>(val initial: T) {
                var top: T = initial
            }
            fun main(args: Array<String>) {
                val s = Stack<Int>(0)
            }
        """)
        // Both fields should be ktc_Int (T→Int)
        r.headerMatches(Regex("ktc_Int initial;"))
        r.headerMatches(Regex("ktc_Int top;"))
    }

    // ── TypeRef scanning for generic instantiation in type positions ─

    @Test fun genericTypeInVarDeclTypeAnnotation() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun main(args: Array<String>) {
                val b: Box<Int> = Box<Int>(42)
            }
        """)
        // Should still produce concrete Box_Int
        r.headerContains("test_Main_Box_Int")
        r.sourceContains("test_Main_Box_Int_primaryConstructor(42)")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Generic function monomorphization
    // ═══════════════════════════════════════════════════════════════════

    @Test fun genericFunBasicInstantiation() {
        val r = transpile("""
            package test.Main
            fun <T> identity(x: T): T {
                return x
            }
            fun main(args: Array<String>) {
                val a = identity<Int>(42)
            }
        """)
        // Mangled function name: identity_Int
        r.headerContains("test_Main_identity_Int(")
        r.sourceContains("ktc_Int test_Main_identity_Int(ktc_Int x)")
        r.sourceContains("test_Main_identity_Int(42)")
    }

    @Test fun genericFunTypeInference() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun <T> unbox(b: Box<T>): T {
                return b.item
            }
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
                val v = unbox(b)
            }
        """)
        // Type arg inferred from Box<Int> argument
        r.sourceContains("test_Main_unbox_Int(")
    }

    @Test fun genericFunMultipleInstantiations() {
        val r = transpile("""
            package test.Main
            fun <T> identity(x: T): T {
                return x
            }
            fun main(args: Array<String>) {
                val a = identity<Int>(42)
                val b = identity<Float>(3.14f)
            }
        """)
        // Both instantiations emitted
        r.headerContains("test_Main_identity_Int(")
        r.headerContains("test_Main_identity_Float(")
        r.sourceContains("test_Main_identity_Int(42)")
        r.sourceContains("test_Main_identity_Float(3.14f)")
    }

    @Test fun genericFunTemplateNotEmitted() {
        val r = transpile("""
            package test.Main
            fun <T> identity(x: T): T {
                return x
            }
            fun main(args: Array<String>) {
                val a = identity<Int>(42)
            }
        """)
        // The generic template itself should NOT appear as a bare function
        r.sourceNotContains("test_Main_identity(")
    }

    @Test fun genericFunWithGenericClassParam() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun <T> getItem(b: Box<T>): T {
                return b.item
            }
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
                val v = getItem(b)
            }
        """)
        // Function should reference the mangled class type
        r.sourceContains("test_Main_getItem_Int(")
    }

    @Test fun genericFunVoidReturn() {
        val r = transpile("""
            package test.Main
            fun <T> printVal(x: T) {
                println(x)
            }
            fun main(args: Array<String>) {
                printVal<Int>(42)
            }
        """)
        r.sourceContains("void test_Main_printVal_Int(ktc_Int x)")
        r.sourceContains("test_Main_printVal_Int(42)")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Star-projection extension functions
    // ═══════════════════════════════════════════════════════════════════

    @Test fun starExtBasicEmission() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T) {
                var count: Int = 0
            }
            fun Box<*>.getCount(): Int {
                return count
            }
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
                val c = b.getCount()
            }
        """)
        // Star ext expands to Box_Int_getCount
        r.sourceContains("test_Main_Box_Int_getCount(")
        r.sourceMatches(Regex("ktc_Int test_Main_Box\\_Int_getCount"))
    }

    @Test fun starExtMultipleInstantiations() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun Box<*>.describe(): Int {
                return 0
            }
            fun main(args: Array<String>) {
                val a = Box<Int>(42)
                val b = Box<Float>(3.14f)
                a.describe()
                b.describe()
            }
        """)
        // Star ext emitted once per instantiation
        r.sourceContains("test_Main_Box_Int_describe(")
        r.sourceContains("test_Main_Box_Float_describe(")
    }

    @Test fun starExtAccessesSelfField() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun Box<*>.getItem(): Int {
                return 0
            }
            fun main(args: Array<String>) {
                val b = Box<Int>(42)
                b.getItem()
            }
        """)
        // Extension receives $self pointer
        r.sourceMatches(Regex("test_Main_Box\\_Int\\* \\\$self"))
    }

    @Test fun starExtNoDuplicateEmission() {
        val r = transpile("""
            package test.Main
            class Box<T>(val item: T)
            fun Box<*>.tag(): Int {
                return 1
            }
            fun main(args: Array<String>) {
                val a = Box<Int>(10)
                val b = Box<Int>(20)
                a.tag()
                b.tag()
            }
        """)
        // Even though Box<Int> is used twice, star ext should emit only once
        val count = Regex("test_Main_Box\\_Int_tag\\(").findAll(r.source).count()
        // Implementation + one call per usage = more than 1, but the definition itself appears once
        val implCount = Regex("ktc_Int test_Main_Box\\_Int_tag\\(test_Main_Box\\_Int\\*").findAll(r.source).count()
        assertTrue(implCount == 1, "Star ext should be emitted exactly once, got $implCount\n${r.source}")
    }

    @Test fun starExtWithReturnType() {
        val r = transpile("""
            package test.Main
            class Wrapper<T>(var value: T)
            fun Wrapper<*>.reset(): Int {
                return 0
            }
            fun main(args: Array<String>) {
                val w = Wrapper<Int>(42)
                val r = w.reset()
            }
        """)
        r.sourceMatches(Regex("ktc_Int test_Main_Wrapper\\_Int_reset"))
    }

    // ── Interface method categorization in generic classes ─────────

    /* Verify that methods from a generic class implementing a multi-level interface
    hierarchy are sorted into per-interface sections, not dumped under constructors. */
    @Test fun genericClassIfaceMethodsCategorized() {
        val r = transpileWithStdlib("""
            package test.Main
            fun main(args: Array<String>) {
                val alloc = HeapAllocator.instance
                val l = ArrayList<Int>(alloc, 4)
                l.add(1)
                val x = l.get(0)
            }
        """)
        val vHdr = r.header
        // Find the ArrayList_Int class block
        val vClsStart = vHdr.indexOf("class ArrayList<Int>")
        assertTrue(vClsStart >= 0, "ArrayList<Int> class block not found in header")
        val vClsEnd = vHdr.indexOf("END class ArrayList", vClsStart)
        val vBlock = vHdr.substring(vClsStart, vClsEnd)

        // Locate constructor section end (first ════ section after constructors)
        val vCtorLine = vBlock.indexOf("// ════ constructors ════")
        val vFirstImpl = vBlock.indexOf("// ════ implements", vCtorLine + 1)
        val vCtorBlock = vBlock.substring(0, vFirstImpl)

        // add(), set(), removeAt(), clear() are MutableList-only — must NOT be in ctor block
        assertTrue(!vCtorBlock.contains("KTC_METHOD(void, add)"),
            "add() should be under implements, not constructors; block:\n$vBlock")
        assertTrue(!vCtorBlock.contains("KTC_METHOD(void, clear)"),
            "clear() should be under implements, not constructors; block:\n$vBlock")
        // get() is List-only — must NOT be in ctor block
        assertTrue(!vCtorBlock.contains("KTC_METHOD(ktc_Int, get)"),
            "get() should be under implements, not constructors; block:\n$vBlock")

        // MutableList<T> section must contain add/set/removeAt/clear
        val vMutListIdx = vBlock.indexOf("// ════ implements MutableList<T> ════")
        assertTrue(vMutListIdx >= 0, "Missing MutableList<T> implements section; block:\n$vBlock")
        // List<Int> transitive section must exist and contain get/contains/indexOf/iterator
        val vListIdx = vBlock.indexOf("// ════ implements List<Int> (transitive) ════")
        assertTrue(vListIdx >= 0, "Missing List<Int> (transitive) section; block:\n$vBlock")
        assertTrue(vListIdx > vMutListIdx, "List transitive section must come after MutableList section")

        val vMutListSection = vBlock.substring(vMutListIdx, vListIdx)
        val vListSection = vBlock.substring(vListIdx,
            vBlock.indexOf("// ════", vListIdx + 1).takeIf { it > 0 } ?: vBlock.length)
        assertTrue(vMutListSection.contains("KTC_METHOD(void, add)"),
            "add() should be in MutableList<T> section")
        assertTrue(vListSection.contains("KTC_METHOD(ktc_Int, get)"),
            "get() should be in List<Int> (transitive) section")
    }
}
