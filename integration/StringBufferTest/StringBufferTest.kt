package test

data class Vec2(val x: Float, val y: Float)
data class Point(val x: Int)
data class Person(val name: String, val age: Int)
data class Line(val start: Vec2, val end: Vec2)

class Foo(val x: Int)

inline fun testToStringViaFunction(sb: StringBuffer, p: Point): String {
    return p.toString(sb)
}

fun main() {
    // 1. Data class toString with stack buffer
    val v = Vec2(1.5f, 2.5f)
    val buf1 = CharArray(256)
    var sb1 = StringBuffer(buf1.ptr(), 0)
    val s1 = v.toString(sb1)
    if (s1 != "Vec2(x=1.5, y=2.5)") error("FAIL 1: dataClassStackBuffer, expected: \"Vec2(x=1.5, y=2.5)\", got: \"$s1\"")

    // 2. Counting mode (null buffer) then write
    val p2 = Point(42)
    var sbNull = StringBuffer(null, 0)
    p2.toString(sbNull)
    val needed = sbNull.len
    if (needed <= 0) error("FAIL 2: nullBufferCounting needed=$needed")
    val buf2 = CharArray(needed + 1)
    var sb2 = StringBuffer(buf2.ptr(), 0)
    val s2 = p2.toString(sb2)
    if (s2 != "Point(x=42)") error("FAIL 2: nullBufferCounting str=$s2")

    // 3. Int toString
    val iv = -12345
    val buf3 = CharArray(32)
    var sb3 = StringBuffer(buf3.ptr(), 0)
    val s3 = iv.toString(sb3)
    if (s3 != "-12345") error("FAIL 3: intToString")

    // 4. Double toString
    val dv = 3.14159
    val buf4 = CharArray(32)
    var sb4 = StringBuffer(buf4.ptr(), 0)
    val s4 = dv.toString(sb4)
    if (s4 != "3.14159") error("FAIL 4: doubleToString, expected: \"3.141590\", got: \"$s4\"")

    // 5. Boolean toString
    val buf5 = CharArray(32)
    var sb5 = StringBuffer(buf5.ptr(), 0)
    val s5 = true.toString(sb5)
    if (s5 != "true") error("FAIL 5: booleanToString")

    // 6. Nested data class toString
    val start = Vec2(0f, 0f)
    val endV = Vec2(10f, 20f)
    val line = Line(start, endV)
    val buf6 = CharArray(512)
    var sb6 = StringBuffer(buf6.ptr(), 0)
    val s6 = line.toString(sb6)
    if (s6 != "Line(start=Vec2(x=0.0, y=0.0), end=Vec2(x=10.0, y=20.0))") error("FAIL 6: nestedDataClassToString")

    // 7. StringBuffer passed as function parameter
    val p7 = Point(99)
    val buf7 = CharArray(128)
    var sb7 = StringBuffer(buf7.ptr(), 0)
    val s7 = testToStringViaFunction(sb7, p7)
    if (s7 != "Point(x=99)") error("FAIL 7: stringBufferAsParameter")

    // 8. Heap-allocated buffer
    val p8 = Point(7)
    val bufHeap = HeapAlloc<Array<Char>>(256)
    var sb8 = StringBuffer(bufHeap, 0)
    val s8 = p8.toString(sb8)
    if (s8 != "Point(x=7)") error("FAIL 8: heapBufferToString")
    Heap.freeMem(bufHeap)

    // 9. StringBuffer field access
    val buf9 = CharArray(128)
    var sb9 = StringBuffer(buf9.ptr(), 0)
    val ptr9 = sb9.buffer
    val len9 = sb9.len
    if (ptr9 == null || len9 != 0) error("FAIL 9: stringBufferFieldAccess")

    // 10. Person data class (String field)
    val p10 = Person("Alice", 30)
    val buf10 = CharArray(256)
    var sb10 = StringBuffer(buf10.ptr(), 0)
    val s10 = p10.toString(sb10)
    if (s10 != "Person(name=Alice, age=30)") error("FAIL 10: personDataClass")

    // 11. Reuse StringBuffer (reset len)
    val v11a = Vec2(1f, 2f)
    val v11b = Vec2(3f, 4f)
    val buf11 = CharArray(512)
    var sb11 = StringBuffer(buf11.ptr(), 0)
    val s11a = v11a.toString(sb11)
    if (s11a != "Vec2(x=1.0, y=2.0)") error("FAIL 11: reuse1")
    sb11.len = 0
    val s11b = v11b.toString(sb11)
    if (s11b != "Vec2(x=3.0, y=4.0)") error("FAIL 11: reuse2")

    // 12. Default class toString with StringBuffer
    val f12 = Foo(1)
    val buf12 = CharArray(128)
    var sb12 = StringBuffer(buf12.ptr(), 0)
    val s12 = f12.toString(sb12)
    if (s12.len == 0) error("FAIL 12: defaultClassToString empty")

    println("ALL OK")
}
