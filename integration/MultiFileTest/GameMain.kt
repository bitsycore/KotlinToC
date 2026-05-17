package game

import math.*

class Test {
    var a: Int = 5
}

fun main() {
    val a = HeapAlloc<Test>()
    if (a == null) return
    defer Heap.freeMem(a)
    println(a)
    val pts = Point(1, 2)
    println(pts)

    val b = Vec3(4.0f, 5.0f, 6.0f)
    println(b)
    val dotResult = dot(b, b)
    println(dotResult)
    if (dotResult != 77.0f) error("FAIL dot product: $dotResult")

    println("done")
}