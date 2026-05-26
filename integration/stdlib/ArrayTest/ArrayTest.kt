package ArrayTest

fun arrayPtr(arr: Ref<Array<Int>>) {
    if (arr.size < 0) error("negative size")
}

fun arrayPtrWithDispose(arr: Ref<Array<Int>>) {
    if (arr.size < 0) error("negative size")
    Heap.freeMem(arr)
}

fun testArrayPtr() {
    // STACK ARRAY TO PTR

    val arr = Array<Int>(10)
    arrayPtr(arr.asRef())

    arrayPtr(Array<Int>(10).asRef())

    val arrOther = Array<Int>(10)
    arrayPtr(arrOther.asRef())

    arrayPtr(Array<Int>(10).asRef())

    arrayPtr(arrayOf(1,2,3,4,5).asRef())

    // HEAP ARRAY TO PTR

    arrayPtrWithDispose(arrayOf(0,1,2,3,4,5).copyWith(Heap))
    val knownArr = arrayOf(10, 20, 30).copyWith(Heap)
    arrayPtrWithDispose(knownArr)
    val arrHeap = arrayOf(0,1,2,3,4,5).copyWith(Heap)
    arrayPtrWithDispose(arrHeap)

    // ARRAY COPY PTR

    val arr2 = arrayOf(1,2,3,4,5).asRef()
    val arr3 = arr2
    val arr4: Ref<Array<Int>> = arr3
    arrayPtr(arr2)
    arrayPtr(arr3)
    arrayPtr(arr4)

    // ARRAY copyWith

    val arr5 = arrayOf(1,2,3,4,5).copyWith(Heap)
    defer Heap.freeMem(arr5)
    val arr6 = arr5
    val arr7: Ref<Array<Int>> = arr6
    arrayPtr(arr5)
    arrayPtr(arr6)
    arrayPtr(arr7)
}

fun testArrayInt(): Ref<Array<Int>> {

    // arr

    val arr = intArrayOf(10, 20, 30, 40, 50)
    if (arr.size != 5) error("size should be 5")
    println("size = ${arr.size}")

    for (i in 0 until arr.size) {
        println(arr[i])
    }

    arr[1] = 99
    println("after set: ${arr[1]}")
    if (arr[1] != 99) error("arr[1] should be 99")

    // arr1

    val arr1 = arrayOf(10, 12, 14)
    if (arr1.size != 3) error("size should be 3")
    println("size = ${arr1.size}")

    for (i in 0 until arr1.size) {
        println(arr1[i])
    }

    arr1[1] = 99
    println("after set: ${arr1[1]}")
    if (arr1[1] != 99) error("arr1[1] should be 99")

    // arr2

    val arr2 = Array<Int>(3)
    if (arr2.size != 3) error("size should be 3")
    println("size = ${arr2.size}")

    for (i in 0 until arr2.size) {
        println(arr2[i])
    }

    arr2[1] = 99
    println("after set: ${arr2[1]}")
    if (arr2[1] != 99) error("arr2[1] should be 99")

    // arr3

    val arr3: Ref<Array<Int>>? = arrayOf(5, 10, 15, 20).copyWith(Heap)
    arr3?.let { it ->
        if (it.size != 4) error("size should be 4")
        println("size = ${it.size}")

        for (i in 0 until it.size) {
            println(it[i])
        }

        it[1] = 99
        println("after set: ${it[1]}")
        if (it[1] != 99) error("arr3[1] should be 99")
    }

//    if (arr3?.size != 4) error("size should be 4")
//    println("size = ${arr3?.size}")
//
//    for (i in 0 until arr3?.size) {
//        println(arr3?.get(i))
//    }
//
//    arr3?.set(1, 99)
//    println("after set: ${arr3?.get(1)}")
//    if (arr3?.get(1) != 99) error("arr3[1] should be 99")

    return arr3
}

fun testFill() {
    // Byte-sized element → memset with the value byte.
    val barr = ByteArray(5)
    barr.fill(7)
    for (i in 0 until barr.size) {
        if (barr[i] != 7.toByte()) error("byte fill failed at $i")
    }

    // Zero literal on non-byte element → memset 0.
    val iarr = IntArray(6)
    iarr.fill(0)
    for (i in 0 until iarr.size) {
        if (iarr[i] != 0) error("int zero fill failed at $i")
    }

    // Non-zero, non-byte element → element loop.
    iarr.fill(42)
    for (i in 0 until iarr.size) {
        if (iarr[i] != 42) error("int loop fill failed at $i")
    }

    // Ranged fill: only [2, 5) changes.
    val rarr = IntArray(8)
    rarr.fill(1)
    rarr.fill(9, 2, 5)
    for (i in 0 until 8) {
        val expected = if (i >= 2 && i < 5) 9 else 1
        if (rarr[i] != expected) error("ranged fill failed at $i")
    }

    // RawArray needs an explicit count.
    val raw: Ref<RawArray<Int>> = RawArray<Int>(4).allocWith(Heap)!!
    raw.fill(4, 9)
    for (i in 0 until 4) {
        if (raw[i] != 9) error("raw fill failed at $i")
    }
    // RawArray ranged fill: only [1, 3) changes.
    raw.fill(4, 0, 1, 3)
    for (i in 0 until 4) {
        val expected = if (i >= 1 && i < 3) 0 else 9
        if (raw[i] != expected) error("raw ranged fill failed at $i")
    }
    Heap.freeMem(raw)

    println("fill ok")
}

fun testAliasing() {
    val arr = IntArray(4)
    arr.fill(3)

    // asRaw() aliases the same data (no copy).
    val raw: Ref<RawArray<Int>> = arr.asRaw()
    raw[0] = 100
    if (arr[0] != 100) error("asRaw should alias the array data")

    // asArray(n) re-views the raw pointer with a length, sharing the same memory.
    val view: Ref<Array<Int>> = raw.asArray(4)
    if (view.size != 4) error("asArray size should be 4")
    view[1] = 200
    if (arr[1] != 200) error("asArray should alias the same memory")

    println("alias ok")
}

fun testRawResize() {
    // Grow: first N elements must survive the realloc.
    var raw: Ref<RawArray<Int>> = RawArray<Int>(4).allocWith(Heap)!!
    for (i in 0 until 4) {
        raw[i] = (i + 1) * 10           // 10, 20, 30, 40
    }
    raw = raw.resizeWith(Heap, 8)
    for (i in 0 until 4) {
        if (raw[i] != (i + 1) * 10) error("raw grow lost data at $i: got ${raw[i]}")
    }
    // The tail past old size is uninitialized — just write through it to prove it's addressable.
    for (i in 4 until 8) {
        raw[i] = i * 100
    }
    for (i in 4 until 8) {
        if (raw[i] != i * 100) error("raw grow tail write failed at $i")
    }

    // Shrink: first M elements still readable, tail is gone (we can't reach it safely).
    raw = raw.resizeWith(Heap, 2)
    if (raw[0] != 10 || raw[1] != 20) error("raw shrink lost head data: ${raw[0]}, ${raw[1]}")

    Heap.freeMem(raw)
    println("raw resize ok")
}

fun testHeapArrayInitLambda() {
    // Array<T>(n) { init }.allocWith(allocator) — lambda must run over the freshly allocated slots.
    val squares: Ref<Array<Int>> = Array<Int>(8) { it -> it * it }.allocWith(Heap)
    defer Heap.freeMem(squares)
    for (i in 0 until 8) {
        if (squares[i] != i * i) error("heap init lambda failed at $i: expected ${i * i}, got ${squares[i]}")
    }

    // Zero-init form (no lambda) — slots are uninitialized memory, but the size is correct.
    val raw: Ref<Array<Int>> = Array<Int>(4).allocWith(Heap)
    defer Heap.freeMem(raw)
    if (raw.size != 4) error("alloc size wrong: ${raw.size}")
    // Write something to prove the buffer is addressable.
    raw[0] = 7; raw[3] = 11
    if (raw[0] != 7 || raw[3] != 11) error("alloc writes failed")

    println("heap init lambda ok")
}

fun testArrayResize() {
    // Array<T> grown via resizeWith preserves the original contents.
    var arr: Ref<Array<Int>> = Array<Int>(4) { it -> it + 1 }.allocWith(Heap)
    if (arr.size != 4) error("array size wrong: ${arr.size}")

    arr = arr.resizeWith(Heap, 8)
    if (arr.size != 8) error("after grow size wrong: ${arr.size}")
    for (i in 0 until 4) {
        if (arr[i] != i + 1) error("Array.resizeWith lost data at $i: got ${arr[i]}")
    }
    // Tail past old size: write to prove it's reachable.
    for (i in 4 until 8) {
        arr[i] = i + 100
    }
    for (i in 4 until 8) {
        if (arr[i] != i + 100) error("Array.resizeWith tail unwritable at $i")
    }

    // Shrink: head must survive.
    arr = arr.resizeWith(Heap, 2)
    if (arr.size != 2) error("after shrink size wrong: ${arr.size}")
    if (arr[0] != 1 || arr[1] != 2) error("Array.resizeWith shrink head: ${arr[0]}, ${arr[1]}")

    Heap.freeMem(arr)
    println("array resize ok")
}

fun testArrayCopyWith() {
    // copyWith allocates a new buffer with the same data — modifying the copy must not touch the original.
    val src: Ref<Array<Int>> = Array<Int>(4) { it -> it * 10 }.allocWith(Heap)
    defer Heap.freeMem(src)
    val dst: Ref<Array<Int>> = src.copyWith(Heap)
    defer Heap.freeMem(dst)

    if (dst.size != src.size) error("copy size mismatch")
    for (i in 0 until 4) {
        if (dst[i] != src[i]) error("copy content mismatch at $i")
    }
    // Mutate the copy and verify isolation.
    dst[0] = 999
    if (src[0] != 0) error("copyWith aliased the source! src[0]=${src[0]}")

    println("array copyWith ok")
}

fun testPairTripleToList() {
    // Pair<T,T>.toList(allocator) — generic extension forwarding to listOf.
    val pair  = 10 to 20
    val list2 = pair.toList(Heap)
    defer list2.dispose()
    if (list2.size != 2)    error("pair toList size: ${list2.size}")
    if (list2.get(0) != 10) error("pair toList[0]")
    if (list2.get(1) != 20) error("pair toList[1]")

    // Triple<T,T,T>.toList(allocator).
    val triple = Triple(1, 2, 3)
    val list3  = triple.toList(Heap)
    defer list3.dispose()
    if (list3.size != 3)    error("triple toList size: ${list3.size}")
    if (list3.get(2) != 3)  error("triple toList[2]")

    println("pair/triple toList ok")
}

fun main() {
    testArrayPtr()
    testFill()
    testAliasing()
    testRawResize()
    testHeapArrayInitLambda()
    testArrayResize()
    testArrayCopyWith()
    testPairTripleToList()



    // arrayOf<String>
    val names = arrayOf("Alice", "Bob", "Charlie")
    if (names.size != 3) error("size should be 3")
    for (name in names) {
        println("\"$name\" is ${name.length} characters long")
    }

    val farr = floatArrayOf(1.5f, 2.5f, 3.5f)
    val farr2 = Array<Float>(3)
    val farr3 = arrayOf(2f, 4f, 6f)

    val darr = doubleArrayOf(1.5, 2.5, 3.5)
    val darr2 = arrayOf(3.4, 2.2, 3.5)
    val darr3 = Array<Double>(3)
    if (farr.size != 3 || farr2.size != 3 || farr3.size != 3
        || darr.size != 3 || darr2.size != 3 || darr3.size != 3)
        error("size should be 3")

    val larr = longArrayOf(100L, 200L, 300L)
    val larr2 = arrayOf<Long>(100L, 200L, 300L)
    val larr3 = Array<Long>(3)
    if (larr.size != 3 || larr2.size != 3 || larr3.size != 3)
        error("size should be 3")

    val sarr = arrayOf<Short>(10, 20, 30)
    val sarr2 = Array<Short>(3)
    if (sarr.size != 3 || sarr2.size != 3)
        error("size should be 3")

    val barr = arrayOf<Byte>(10, 20, 30)
    val barr2 = Array<Byte>(3)
    if (barr.size != 3 || barr2.size != 3)
        error("size should be 3")

    println("done")
}
