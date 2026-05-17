package com.bitsycore.ktc

import kotlin.test.Test

class OperatorOverloadUnitTest : TranspilerTestBase() {

    @Test fun operatorGetIndex() {
        val r = transpile("""
            package test.Main
            class IntMap(val arr: @Ptr IntArray) {
                operator fun get(index: Int): Int = arr[index]
            }
            fun main(args: Array<String>) {
                val arr = intArrayOf(10, 20, 30)
                val m = IntMap(arr.ptr())
                val v = m[1]
            }
        """)
        r.sourceContains("IntMap_get(&m, 1)")
    }

    @Test fun operatorSetIndex() {
        val r = transpile("""
            package test.Main
            class IntMap(var arr: @Ptr IntArray) {
                operator fun set(index: Int, value: Int) {
                    arr[index] = value
                }
            }
            fun main(args: Array<String>) {
                val arr = intArrayOf(10, 20, 30)
                val m = IntMap(arr.ptr())
                m[1] = 99
            }
        """)
        r.sourceContains("IntMap_set(&m, 1, 99)")
    }

    @Test fun operatorContains() {
        val r = transpile("""
            package test.Main
            class Range2(val lo: Int, val hi: Int) {
                operator fun contains(x: Int): Boolean = x >= lo && x <= hi
            }
            fun main(args: Array<String>) {
                val r = Range2(1, 10)
                val ok = 5 in r
            }
        """)
        r.sourceContains("Range2_contains(&r, 5)")
    }

    @Test fun operatorContainsNotIn() {
        val r = transpile("""
            package test.Main
            class Range2(val lo: Int, val hi: Int) {
                operator fun contains(x: Int): Boolean = x >= lo && x <= hi
            }
            fun main(args: Array<String>) {
                val r = Range2(1, 10)
                val ok = 15 !in r
            }
        """)
        r.sourceContains("!test_Main_Range2_contains(&r, 15)")
    }

    @Test fun operatorGetOnInterface() {
        val r = transpile("""
            package test.Main
            interface Indexed {
                operator fun get(index: Int): Int
            }
            class IntList(val arr: @Ptr IntArray) : Indexed {
                override fun get(index: Int): Int = arr[index]
            }
            fun main(args: Array<String>) {
                val arr = intArrayOf(1, 2, 3).ptr()
                val lst: Indexed = IntList(arr)
                val v = lst[0]
            }
        """)
        r.sourceContains("lst.vt->get((void*)&lst.test_Main_IntList_data, 0)")
    }

    @Test fun operatorSetOnInterface() {
        val r = transpile("""
            package test.Main
            interface MutableIndexed {
                operator fun set(index: Int, value: Int)
            }
            class IntList(var arr: @Ptr IntArray) : MutableIndexed {
                override fun set(index: Int, value: Int) { arr[index] = value }
            }
            fun main(args: Array<String>) {
                val arr = intArrayOf(1, 2, 3)
                val lst: MutableIndexed = IntList(arr.ptr())
                lst[0] = 99
            }
        """)
        r.sourceContains("lst.vt->set((void*)&lst.test_Main_IntList_data, 0, 99)")
    }

    @Test fun operatorIterator() {
        val r = transpileWithStdlib("""
            package test.Main
            class IntRange2(val lo: Int, val hi: Int) {
                operator fun iterator(): Iterator<Int> = TODO()
            }
            fun main(args: Array<String>) {
                val r = IntRange2(1, 10)
                for (x in r) { }
            }
        """)
        r.sourceContains("IntRange2_iterator")
    }

    @Test fun operatorIteratorWithStdlib() {
        val r = transpileMainWithStdlib("""
            val list = mutableListOf(1, 2, 3)
            var sum = 0
            for (x in list) { sum += x }
        """)
        r.sourceContains("ktc_std_ArrayList_Int_iterator")
    }

    @Test fun operatorContainsMap() {
        val r = transpileMainWithStdlib("""
            val map = mutableMapOf("a" to 1, "b" to 2)
            val has = "a" in map
        """)
        r.sourceContains("containsKey")
    }

    @Test fun operatorGetMap() {
        val r = transpileMainWithStdlib("""
            val map = mutableMapOf("a" to 1, "b" to 2)
            val v = map["a"]
        """)
        r.sourceContains("ktc_std_HashMap_String_Int_get")
    }

    @Test fun operatorSetMap() {
        val r = transpileMainWithStdlib("""
            val map = mutableMapOf("a" to 1, "b" to 2)
            map["a"] = 99
        """)
        r.sourceContains("ktc_std_HashMap_String_Int_set")
    }
}
