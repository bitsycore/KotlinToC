package ArrayTest

fun arrayPtr(arr: @Ptr Array<Int>) {
    if (arr.size < 0) error("negative size")
}

fun arrayPtrWithDispose(arr: @Ptr Array<Int>) {
    if (arr.size < 0) error("negative size")
    Heap.freeMem(arr)
}

fun testArrayPtr() {
    // STACK ARRAY TO PTR

    val arr = Array<Int>(10)
    arrayPtr(arr.ptr())

    arrayPtr(Array<Int>(10).ptr())

    val arrOther = Array<Int>(10)
    arrayPtr(arrOther.ptr())

    arrayPtr(Array<Int>(10).ptr())

    arrayPtr(arrayOf(1,2,3,4,5).ptr())

    // HEAP ARRAY TO PTR

    arrayPtrWithDispose(arrayOf(0,1,2,3,4,5).copyWith(Heap))
    val knownArr = arrayOf(10, 20, 30).copyWith(Heap)
    arrayPtrWithDispose(knownArr)
    val arrHeap = arrayOf(0,1,2,3,4,5).copyWith(Heap)
    arrayPtrWithDispose(arrHeap)

    // ARRAY COPY PTR

    val arr2 = arrayOf(1,2,3,4,5).ptr()
    val arr3 = arr2
    val arr4: @Ptr Array<Int> = arr3
    arrayPtr(arr2)
    arrayPtr(arr3)
    arrayPtr(arr4)

    // ARRAY copyWith

    val arr5 = arrayOf(1,2,3,4,5).copyWith(Heap)
    defer Heap.freeMem(arr5)
    val arr6 = arr5
    val arr7: @Ptr Array<Int> = arr6
    arrayPtr(arr5)
    arrayPtr(arr6)
    arrayPtr(arr7)
}

fun testArrayInt(): @Ptr Array<Int> {

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

    val arr3: @Ptr Array<Int>? = arrayOf(5, 10, 15, 20).copyWith(Heap)
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
    val raw: @Ptr RawArray<Int> = RawArray<Int>.allocWith(Heap, 4)!!
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
    val raw: @Ptr RawArray<Int> = arr.asRaw()
    raw[0] = 100
    if (arr[0] != 100) error("asRaw should alias the array data")

    // asArray(n) re-views the raw pointer with a length, sharing the same memory.
    val view: @Ptr Array<Int> = raw.asArray(4)
    if (view.size != 4) error("asArray size should be 4")
    view[1] = 200
    if (arr[1] != 200) error("asArray should alias the same memory")

    println("alias ok")
}

fun main() {
    testArrayPtr()
    testFill()
    testAliasing()



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
