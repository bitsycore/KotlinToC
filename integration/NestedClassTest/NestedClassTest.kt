package test

class Outer {
    class Inner(val x: Int)
    class Helper(val name: String)
}

data class Vec2(val x: Float, val y: Float) {
    class NestedPair(val a: Int, val b: Int)
}

class A {
    class B {
        class C(val v: Int)
    }
}

fun main() {
    val inner = Outer.Inner(42)
    if (inner.x != 42) error("FAIL simple nested x=${inner.x}")

    val h = Outer.Helper("test")
    if (h.name != "test") error("FAIL nested string name=${h.name}")

    val np = Vec2.NestedPair(1, 2)
    if (np.a != 1 || np.b != 2) error("FAIL data class nested a=${np.a} b=${np.b}")

    val deep = A.B.C(99)
    if (deep.v != 99) error("FAIL deeply nested v=${deep.v}")

    println("ALL OK")
}
