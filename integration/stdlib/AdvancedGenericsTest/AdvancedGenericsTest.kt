package advanced.generics

fun arrayListAllocTest() {
    println("\n============ arrayListAllocTest ============\n")

    // alloc ArrayList on stack, right infered
    val stack1 = ArrayList<Int>(allocator = Heap, capacity = 4)
    defer { stack1.dispose() }
    // alloc ArrayList on stack, left infered
    val stack2: ArrayList<Float> = ArrayList(Heap, 8)
    defer { stack2.dispose() }
    // alloc ArrayList on heap, right infered
    val heap1 = ArrayList<Int>(Heap, 2).allocWith(Heap)
    defer {
        heap1.dispose()
        Heap.freeMem(heap1)
    }
    // alloc ArrayList on heap, left infered
    val heap2: Ref<ArrayList<Float>> = ArrayList(Heap, 1).allocWith(Heap)
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
    testValue(heap1.refValue)
    testValue(heap2.refValue)

    testPtr(stack1.asRef())
    testPtr(stack2.asRef())
    testPtr(heap1)
    testPtr(heap2)

    // CONCRETE EXTENSION

    stack1.testValueExt()
    stack2.testValueExt()
    heap1.refValue.testValueExt()
    heap2.refValue.testValueExt()

    stack1.asRef().testPtrExt()
    stack2.asRef().testPtrExt()
    heap1.testPtrExt()
    heap2.testPtrExt()

    // INTERFACE

    testListValue(stack1)
    testListValue(stack2)
    testListValue(heap1.refValue)
    testListValue(heap2.refValue)

    testListPtr(stack1.asRef())
    testListPtr(stack2.asRef())
    testListPtr(heap1)
    testListPtr(heap2)

    // INTERFACE EXTENSION

    stack1.testListValueExt()
    stack2.testListValueExt()
    heap1.refValue.testListValueExt()
    heap2.refValue.testListValueExt()

    stack1.asRef().testListPtrExt()
    stack2.asRef().testListPtrExt()
    heap1.testListPtrExt()
    heap2.testListPtrExt()

    // ===============================================
    // MARK: NULLABLE
    // ===============================================

    val heap1Null1: Ref<ArrayList<Int>?> = heap1
    val heap1Null2: Ref<ArrayList<Int>?> = null
    val stack1Null1: ArrayList<Int>? = stack1.copy()
    val stack1Null2: ArrayList<Int>? = null

    // CONCRETE NULLABLE

    testValueNullable(stack1)
    testValueNullable(stack2)
    testValueNullable(heap1.refValue)
    testValueNullable(heap2.refValue)

    testValueNullable(null)
    heap1Null1?.let { a -> testValueNullable(a.refValue) }
    heap1Null2?.let { a -> testValueNullable(a.refValue) }
    testValueNullable(stack1Null1)
    testValueNullable(stack1Null2)

    testPtrNullable(stack1.asRef())
    testPtrNullable(stack2.asRef())
    testPtrNullable(heap1)
    testPtrNullable(heap2)

    testPtrNullable(null)
    testPtrNullable(stack1Null1.asRef())
    testPtrNullable(stack1Null2.asRef())
    testPtrNullable(heap1Null1)
    testPtrNullable(heap1Null2)

    // CONCRETE EXTENSION NULLABLE
    stack1.testValueExtNullable()
    stack2.testValueExtNullable()
    heap1.refValue.testValueExtNullable()
    heap2.refValue.testValueExtNullable()

    heap1Null1?.refValue?.testValueExtNullable()
    heap1Null2?.refValue?.testValueExtNullable()
    stack1Null1.testValueExtNullable()
    stack1Null2.testValueExtNullable()

    stack1.asRef().testPtrExtNullable()
    stack2.asRef().testPtrExtNullable()
    heap1.testPtrExtNullable()
    heap2.testPtrExtNullable()

    heap1Null1.testPtrExtNullable()
    heap1Null2.testPtrExtNullable()
    stack1Null1.asRef().testPtrExtNullable()
    stack1Null2.asRef().testPtrExtNullable()

    // INTERFACE NULLABLE — interface unions need Optional + for-loop support
    testListValueNullable(stack1)
    testListValueNullable(stack2)
    testListValueNullable(heap1.refValue)
    testListValueNullable(heap2.refValue)

    testListValueNullable(null)
    testListValueNullable(stack1Null1)
    testListValueNullable(stack1Null2)

    testListPtrNullable(stack1.asRef())
    testListPtrNullable(stack2.asRef())
    testListPtrNullable(heap1)
    testListPtrNullable(heap2)

    testListPtrNullable(null)
    testListPtrNullable(heap1Null1)
    testListPtrNullable(heap1Null2)

    // INTERFACE EXTENSION NULLABLE — need interface ext fun nullable support
    stack1.testListValueExtNullable()
    stack2.testListValueExtNullable()
    heap1.refValue.testListValueExtNullable()
    heap2.refValue.testListValueExtNullable()

    stack1Null1.testListValueExtNullable()
    stack1Null2.testListValueExtNullable()

    stack1.asRef().testListPtrExtNullable()
    stack2.asRef().testListPtrExtNullable()
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
    val heap1 = ArrayList<Int?>(Heap, 2).allocWith(Heap)
    defer {
        heap1.dispose()
        Heap.freeMem(heap1)
    }
    // alloc ArrayList on heap, left infered
    val heap2: Ref<ArrayList<Float?>> = ArrayList(Heap, 4).allocWith(Heap)
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
    val heap1 = ArrayList<Vec3>(Heap, 2).allocWith(Heap)
    defer {
        heap1.dispose()
        Heap.freeMem(heap1)
    }
    // alloc ArrayList on heap, left infered
    val heap2: Ref<ArrayList<Vec3>> = ArrayList(Heap, 1).allocWith(Heap)
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
    testValue(heap1.refValue)
    testValue(heap2.refValue)

    testPtr(stack1.asRef())
    testPtr(stack2.asRef())
    testPtr(heap1)
    testPtr(heap2)

    // CONCRETE EXTENSION

    stack1.testValueExt()
    stack2.testValueExt()
    heap1.refValue.testValueExt()
    heap2.refValue.testValueExt()

    stack1.asRef().testPtrExt()
    stack2.asRef().testPtrExt()
    heap1.testPtrExt()
    heap2.testPtrExt()

    // INTERFACE

    testListValue(stack1)
    testListValue(stack2)
    testListValue(heap1.refValue)
    testListValue(heap2.refValue)

    testListPtr(stack1.asRef())
    testListPtr(stack2.asRef())
    testListPtr(heap1)
    testListPtr(heap2)

    // INTERFACE EXTENSION

    stack1.testListValueExt()
    stack2.testListValueExt()
    heap1.refValue.testListValueExt()
    heap2.refValue.testListValueExt()

    stack1.asRef().testListPtrExt()
    stack2.asRef().testListPtrExt()
    heap1.testListPtrExt()
    heap2.testListPtrExt()

    // ===============================================
    // MARK: NULLABLE
    // ===============================================

    val heap1Null1: Ref<ArrayList<Vec3>?> = heap1
    val heap1Null2: Ref<ArrayList<Vec3>?> = null
    val stack1Null1: ArrayList<Vec2>? = stack1.copy()
    val stack1Null2: ArrayList<Vec2>? = null

    // CONCRETE NULLABLE

    testValueNullable(stack1)
    testValueNullable(stack2)
    testValueNullable(heap1.refValue)
    testValueNullable(heap2.refValue)

    testValueNullable(null)
    heap1Null1?.let { a -> testValueNullable(a.refValue) }
    heap1Null2?.let { a -> testValueNullable(a.refValue) }
    testValueNullable(stack1Null1)
    testValueNullable(stack1Null2)

    testPtrNullable(stack1.asRef())
    testPtrNullable(stack2.asRef())
    testPtrNullable(heap1)
    testPtrNullable(heap2)

    testPtrNullable(null)
    testPtrNullable(stack1Null1.asRef())
    testPtrNullable(stack1Null2.asRef())
    testPtrNullable(heap1Null1)
    testPtrNullable(heap1Null2)

    // CONCRETE EXTENSION NULLABLE
    stack1.testValueExtNullable()
    stack2.testValueExtNullable()
    heap1.refValue.testValueExtNullable()
    heap2.refValue.testValueExtNullable()

    heap1Null1?.refValue?.testValueExtNullable()
    heap1Null2?.refValue?.testValueExtNullable()
    stack1Null1.testValueExtNullable()
    stack1Null2.testValueExtNullable()

    stack1.asRef().testPtrExtNullable()
    stack2.asRef().testPtrExtNullable()
    heap1.testPtrExtNullable()
    heap2.testPtrExtNullable()

    heap1Null1.testPtrExtNullable()
    heap1Null2.testPtrExtNullable()
    stack1Null1.asRef().testPtrExtNullable()
    stack1Null2.asRef().testPtrExtNullable()

    // INTERFACE NULLABLE — interface unions need Optional + for-loop support
    testListValueNullable(stack1)
    testListValueNullable(stack2)
    testListValueNullable(heap1.refValue)
    testListValueNullable(heap2.refValue)

    testListValueNullable(null)
    testListValueNullable(stack1Null1)
    testListValueNullable(stack1Null2)

    testListPtrNullable(stack1.asRef())
    testListPtrNullable(stack2.asRef())
    testListPtrNullable(heap1)
    testListPtrNullable(heap2)

    testListPtrNullable(null)
    testListPtrNullable(heap1Null1)
    testListPtrNullable(heap1Null2)

    // INTERFACE EXTENSION NULLABLE — need interface ext fun nullable support
    stack1.testListValueExtNullable()
    stack2.testListValueExtNullable()
    heap1.refValue.testListValueExtNullable()
    heap2.refValue.testListValueExtNullable()

    stack1Null1.testListValueExtNullable()
    stack1Null2.testListValueExtNullable()

    stack1.asRef().testListPtrExtNullable()
    stack2.asRef().testListPtrExtNullable()
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