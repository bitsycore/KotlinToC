package advancedgenerics

fun arrayListAllocTest() {
    println("\n============ arrayListAllocTest ============\n")

    // alloc ArrayList on stack, right infered
    val stack1 = ArrayList<Int>(allocator = Heap, capacity = 4)
    defer { stack1.dispose() }
    // alloc ArrayList on stack, left infered
    val stack2: ArrayList<Float> = ArrayList(Heap, 8)
    defer { stack2.dispose() }
    // alloc ArrayList on heap, right infered
    val heap1 = ArrayList<Int>.allocWith(Heap, Heap, 2)
    defer {
        heap1.dispose()
        Heap.freeMem(heap1)
    }
    // alloc ArrayList on heap, left infered
    val heap2: @Ptr ArrayList<Float> = ArrayList.allocWith(Heap, Heap, 1)
    defer {
        heap2.dispose()
        Heap.freeMem(heap2)
    }

    // STACK1 TEST

    stack1.add(0)
    stack1.add(8)
    stack1.add(2)
    stack1.add(7)
    stack1.add(12)
    stack1.add(105)

    println("Range")
    for (i in 0..<stack1.size) {
        println("stack1[$i] = ${stack1[i]}")
    }

    println("Iterator")
    var counter = 0
    for (value in stack1) {
        println("stack1[$counter] = ${value}")
        counter++
    }

    // STACK2 TEST

    stack2.add(0.5f)
    stack2.add(8.3f)
    stack2.add(2.0f)

    println("Range")
    for (i in 0..<stack2.size) {
        println("stack2[$i] = ${stack2[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in stack2) {
        println("stack2[$counter] = ${value}")
        counter++
    }

    // HEAP1 TEST

    heap1.add(10)
    heap1.add(20)
    heap1.add(30)
    heap1.add(40)
    heap1.add(50)

    println("Range")
    for (i in 0..<heap1.size) {
        println("heap1[$i] = ${heap1[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in heap1) {
        println("heap1[$counter] = ${value}")
        counter++
    }

    // HEAP2 TEST

    heap2.add(0.10f)

    println("Range")
    for (i in 0..<heap2.size) {
        println("heap2[$i] = ${heap2[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in heap2) {
        println("heap2[$counter] = ${value}")
        counter++
    }

    // ===============================================
    // MARK: NON NULL
    // ===============================================

    // CONCRETE

    testValue(stack1)
    testValue(stack2)
    testValue(heap1.value())
    testValue(heap2.value())

    testPtr(stack1.ptr())
    testPtr(stack2.ptr())
    testPtr(heap1)
    testPtr(heap2)

    // CONCRETE EXTENSION

    stack1.testValueExt()
    stack2.testValueExt()
    heap1.value().testValueExt()
    heap2.value().testValueExt()

    stack1.ptr().testPtrExt()
    stack2.ptr().testPtrExt()
    heap1.testPtrExt()
    heap2.testPtrExt()

    // INTERFACE

    testListValue(stack1)
    testListValue(stack2)
    testListValue(heap1.value())
    testListValue(heap2.value())

    testListPtr(stack1.ptr())
    testListPtr(stack2.ptr())
    testListPtr(heap1)
    testListPtr(heap2)

    // INTERFACE EXTENSION

    stack1.testListValueExt()
    stack2.testListValueExt()
    heap1.value().testListValueExt()
    heap2.value().testListValueExt()

    stack1.ptr().testListPtrExt()
    stack2.ptr().testListPtrExt()
    heap1.testListPtrExt()
    heap2.testListPtrExt()

    // ===============================================
    // MARK: NULLABLE
    // ===============================================

    val heap1Null1: @Ptr ArrayList<Int>? = heap1
    val heap1Null2: @Ptr ArrayList<Int>? = null
    val stack1Null1: ArrayList<Int>? = stack1
    val stack1Null2: ArrayList<Int>? = null

    // CONCRETE NULLABLE

    testValueNullable(stack1)
    testValueNullable(stack2)
    testValueNullable(heap1.value())
    testValueNullable(heap2.value())

    testValueNullable(null)
    heap1Null1?.let { a -> testValueNullable(a.value()) }
    heap1Null2?.let { a -> testValueNullable(a.value()) }
    testValueNullable(stack1Null1)
    testValueNullable(stack1Null2)

    testPtrNullable(stack1.ptr())
    testPtrNullable(stack2.ptr())
    testPtrNullable(heap1)
    testPtrNullable(heap2)

    testPtrNullable(null)
    testPtrNullable(stack1Null1.ptr())
    testPtrNullable(stack1Null2.ptr())
    testPtrNullable(heap1Null1)
    testPtrNullable(heap1Null2)

    // CONCRETE EXTENSION NULLABLE
    stack1.testValueExtNullable()
    stack2.testValueExtNullable()
    heap1.value().testValueExtNullable()
    heap2.value().testValueExtNullable()

    heap1Null1?.value()?.testValueExtNullable()
    heap1Null2?.value()?.testValueExtNullable()
    stack1Null1.testValueExtNullable()
    stack1Null2.testValueExtNullable()

    stack1.ptr().testPtrExtNullable()
    stack2.ptr().testPtrExtNullable()
    heap1.testPtrExtNullable()
    heap2.testPtrExtNullable()

    heap1Null1.testPtrExtNullable()
    heap1Null2.testPtrExtNullable()
    stack1Null1.ptr().testPtrExtNullable()
    stack1Null2.ptr().testPtrExtNullable()

    // INTERFACE NULLABLE — interface unions need Optional + for-loop support
    testListValueNullable(stack1)
    testListValueNullable(stack2)
    testListValueNullable(heap1.value())
    testListValueNullable(heap2.value())

    testListValueNullable(null)
    testListValueNullable(stack1Null1)
    testListValueNullable(stack1Null2)

    testListPtrNullable(stack1.ptr())
    testListPtrNullable(stack2.ptr())
    testListPtrNullable(heap1)
    testListPtrNullable(heap2)

    testListPtrNullable(null)
    testListPtrNullable(heap1Null1)
    testListPtrNullable(heap1Null2)

    // INTERFACE EXTENSION NULLABLE — need interface ext fun nullable support
    stack1.testListValueExtNullable()
    stack2.testListValueExtNullable()
    heap1.value().testListValueExtNullable()
    heap2.value().testListValueExtNullable()

    stack1Null1.testListValueExtNullable()
    stack1Null2.testListValueExtNullable()

    stack1.ptr().testListPtrExtNullable()
    stack2.ptr().testListPtrExtNullable()
    heap1.testListPtrExtNullable()
    heap2.testListPtrExtNullable()

    heap1Null1.testListPtrExtNullable()
    heap1Null2.testListPtrExtNullable()
}

fun arrayListWithNullableAllocTest() {
    println("\n============ arrayListWithNullableAllocTest ============\n")

    // alloc ArrayList on stack, right infered
    val stack1 = ArrayList<Int?>(allocator = Heap, capacity = 16)
    defer { stack1.dispose() }
    // alloc ArrayList on stack, left infered
    val stack2: ArrayList<Float?> = ArrayList(Heap, 2)
    defer { stack2.dispose() }
    // alloc ArrayList on heap, right infered
    val heap1 = ArrayList<Int?>.allocWith(Heap, Heap, 2)
    defer {
        heap1.dispose()
        Heap.freeMem(heap1)
    }
    // alloc ArrayList on heap, left infered
    val heap2: @Ptr ArrayList<Float?> = ArrayList.allocWith(Heap, Heap, 4)
    defer {
        heap2.dispose()
        Heap.freeMem(heap2)
    }

    // STACK1 TEST

    stack1.add(0)
    stack1.add(8)
    stack1.add(2)
    stack1.add(7)
    stack1.add(12)
    stack1.add(105)

    println("Range")
    for (i in 0..<stack1.size) {
        println("stack1[$i] = ${stack1[i]}")
    }

    println("Iterator")
    var counter = 0
    for (value in stack1) {
        println("stack1[$counter] = ${value}")
        counter++
    }

    // STACK2 TEST

    stack2.add(0.5f)
    stack2.add(8.3f)
    stack2.add(2.0f)

    println("Range")
    for (i in 0..<stack2.size) {
        println("stack2[$i] = ${stack2[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in stack2) {
        println("stack2[$counter] = ${value}")
        counter++
    }

    // HEAP1 TEST

    heap1.add(10)
    heap1.add(20)
    heap1.add(30)
    heap1.add(40)
    heap1.add(50)

    println("Range")
    for (i in 0..<heap1.size) {
        println("heap1[$i] = ${heap1[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in heap1) {
        println("heap1[$counter] = ${value}")
        counter++
    }

    // HEAP2 TEST

    heap2.add(0.10f)

    println("Range")
    for (i in 0..<heap2.size) {
        println("heap2[$i] = ${heap2[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in heap2) {
        println("heap2[$counter] = ${value}")
        counter++
    }
}

data class Vec2(val x: Int, val y: Int)
data class Vec3(val x: Int, val y: Int, val z: Int)

fun arrayListDataClassAllocTest() {
    println("\n============ arrayListDataClassAllocTest ============\n")

    // alloc ArrayList on stack, right infered
    val stack1 = ArrayList<Vec2>(allocator = Heap, capacity = 4)
    defer { stack1.dispose() }
    // alloc ArrayList on stack, left infered
    val stack2: ArrayList<Vec2> = ArrayList(Heap, 8)
    defer { stack2.dispose() }
    // alloc ArrayList on heap, right infered
    val heap1 = ArrayList<Vec3>.allocWith(Heap, Heap, 2)
    defer {
        heap1.dispose()
        Heap.freeMem(heap1)
    }
    // alloc ArrayList on heap, left infered
    val heap2: @Ptr ArrayList<Vec3> = ArrayList.allocWith(Heap, Heap, 1)
    defer {
        heap2.dispose()
        Heap.freeMem(heap2)
    }

    // STACK1 TEST

    stack1.add(Vec2(0,1))
    stack1.add(Vec2(1,1))
    stack1.add(Vec2(2,1))
    stack1.add(Vec2(3,1))
    stack1.add(Vec2(0,2))
    stack1.add(Vec2(0,3))

    println("Range")
    for (i in 0..<stack1.size) {
        println("stack1[$i] = ${stack1[i]}")
    }

    println("Iterator")
    var counter = 0
    for (value in stack1) {
        println("stack1[$counter] = ${value}")
        counter++
    }

    // STACK2 TEST

    stack2.add(Vec2(5,1))
    stack2.add(Vec2(5,2))
    stack2.add(Vec2(5,3))

    println("Range")
    for (i in 0..<stack2.size) {
        println("stack2[$i] = ${stack2[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in stack2) {
        println("stack2[$counter] = ${value}")
        counter++
    }

    // HEAP1 TEST

    heap1.add(Vec3(10,0,1))
    heap1.add(Vec3(0,5,1))
    heap1.add(Vec3(3,0,1))
    heap1.add(Vec3(0,2,1))
    heap1.add(Vec3(1,0,1))

    println("Range")
    for (i in 0..<heap1.size) {
        println("heap1[$i] = ${heap1[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in heap1) {
        println("heap1[$counter] = ${value}")
        counter++
    }

    // HEAP2 TEST

    heap2.add(Vec3(405, 103, 105))

    println("Range")
    for (i in 0..<heap2.size) {
        println("heap2[$i] = ${heap2[i]}")
    }

    println("Iterator")
    counter = 0
    for (value in heap2) {
        println("heap2[$counter] = ${value}")
        counter++
    }

    // ===============================================
    // MARK: NON NULL
    // ===============================================

    // CONCRETE

    testValue(stack1)
    testValue(stack2)
    testValue(heap1.value())
    testValue(heap2.value())

    testPtr(stack1.ptr())
    testPtr(stack2.ptr())
    testPtr(heap1)
    testPtr(heap2)

    // CONCRETE EXTENSION

    stack1.testValueExt()
    stack2.testValueExt()
    heap1.value().testValueExt()
    heap2.value().testValueExt()

    stack1.ptr().testPtrExt()
    stack2.ptr().testPtrExt()
    heap1.testPtrExt()
    heap2.testPtrExt()

    // INTERFACE

    testListValue(stack1)
    testListValue(stack2)
    testListValue(heap1.value())
    testListValue(heap2.value())

    testListPtr(stack1.ptr())
    testListPtr(stack2.ptr())
    testListPtr(heap1)
    testListPtr(heap2)

    // INTERFACE EXTENSION

    stack1.testListValueExt()
    stack2.testListValueExt()
    heap1.value().testListValueExt()
    heap2.value().testListValueExt()

    stack1.ptr().testListPtrExt()
    stack2.ptr().testListPtrExt()
    heap1.testListPtrExt()
    heap2.testListPtrExt()

    // ===============================================
    // MARK: NULLABLE
    // ===============================================

    val heap1Null1: @Ptr ArrayList<Vec3>? = heap1
    val heap1Null2: @Ptr ArrayList<Vec3>? = null
    val stack1Null1: ArrayList<Vec2>? = stack1
    val stack1Null2: ArrayList<Vec2>? = null

    // CONCRETE NULLABLE

    testValueNullable(stack1)
    testValueNullable(stack2)
    testValueNullable(heap1.value())
    testValueNullable(heap2.value())

    testValueNullable(null)
    heap1Null1?.let { a -> testValueNullable(a.value()) }
    heap1Null2?.let { a -> testValueNullable(a.value()) }
    testValueNullable(stack1Null1)
    testValueNullable(stack1Null2)

    testPtrNullable(stack1.ptr())
    testPtrNullable(stack2.ptr())
    testPtrNullable(heap1)
    testPtrNullable(heap2)

    testPtrNullable(null)
    testPtrNullable(stack1Null1.ptr())
    testPtrNullable(stack1Null2.ptr())
    testPtrNullable(heap1Null1)
    testPtrNullable(heap1Null2)

    // CONCRETE EXTENSION NULLABLE
    stack1.testValueExtNullable()
    stack2.testValueExtNullable()
    heap1.value().testValueExtNullable()
    heap2.value().testValueExtNullable()

    heap1Null1?.value()?.testValueExtNullable()
    heap1Null2?.value()?.testValueExtNullable()
    stack1Null1.testValueExtNullable()
    stack1Null2.testValueExtNullable()

    stack1.ptr().testPtrExtNullable()
    stack2.ptr().testPtrExtNullable()
    heap1.testPtrExtNullable()
    heap2.testPtrExtNullable()

    heap1Null1.testPtrExtNullable()
    heap1Null2.testPtrExtNullable()
    stack1Null1.ptr().testPtrExtNullable()
    stack1Null2.ptr().testPtrExtNullable()

    // INTERFACE NULLABLE — interface unions need Optional + for-loop support
    testListValueNullable(stack1)
    testListValueNullable(stack2)
    testListValueNullable(heap1.value())
    testListValueNullable(heap2.value())

    testListValueNullable(null)
    testListValueNullable(stack1Null1)
    testListValueNullable(stack1Null2)

    testListPtrNullable(stack1.ptr())
    testListPtrNullable(stack2.ptr())
    testListPtrNullable(heap1)
    testListPtrNullable(heap2)

    testListPtrNullable(null)
    testListPtrNullable(heap1Null1)
    testListPtrNullable(heap1Null2)

    // INTERFACE EXTENSION NULLABLE — need interface ext fun nullable support
    stack1.testListValueExtNullable()
    stack2.testListValueExtNullable()
    heap1.value().testListValueExtNullable()
    heap2.value().testListValueExtNullable()

    stack1Null1.testListValueExtNullable()
    stack1Null2.testListValueExtNullable()

    stack1.ptr().testListPtrExtNullable()
    stack2.ptr().testListPtrExtNullable()
    heap1.testListPtrExtNullable()
    heap2.testListPtrExtNullable()

    heap1Null1.testListPtrExtNullable()
    heap1Null2.testListPtrExtNullable()
}

fun main() {
    arrayListAllocTest()
    arrayListWithNullableAllocTest()
    arrayListDataClassAllocTest() // RUNTIME ERROR ON ITERATOR, WHY ?
    println("all ok")
}