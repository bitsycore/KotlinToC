package advancedgenerics

// ===================================================
// MARK: Concrete
// ===================================================

var testPtrCounter = 0

fun <T> testPtr(list: @Ptr ArrayList<T>) {
    println()
    println("[$testPtrCounter] testPtr(ArrayList)")
    testPtrCounter++
    var counter = 0
    for (value in list) {
        println("ArrayList[$counter] = ${value}")
        counter++
    }
}

var testValueCounter = 0

fun <T> testValue(list: ArrayList<T>) {
    println()
    println("[$testValueCounter] testValue(ArrayList)")
    testValueCounter++
    var counter = 0
    for (value in list) {
        println("ArrayList[$counter] = ${value}")
        counter++
    }
}

var testPtrExtCounter = 0

fun <T> @Ptr ArrayList<T>.testPtrExt() {
    println()
    println("[$testPtrExtCounter] ArrayList.testPtrExt()")
    testPtrExtCounter++
    var counter = 0
    for (value in this) {
        println("ArrayList[$counter] = ${value}")
        counter++
    }
}

var testValueExtCounter = 0

fun <T> ArrayList<T>.testValueExt() {
    println()
    println("[$testValueExtCounter] ArrayList.testValueExt()")
    testValueExtCounter++
    var counter = 0
    for (value in this) {
        println("ArrayList[$counter] = ${value}")
        counter++
    }
}

// ===================================================
// MARK: Interface
// ===================================================

var testListPtrCounter = 0

fun <T> testListPtr(list: @Ptr List<T>) {
    println()
    println("[$testListPtrCounter] testListPtr(List)")
    testListPtrCounter++
    var counter = 0
    for (value in list) {
        println("List[$counter] = ${value}")
        counter++
    }
}

var testListValueCounter = 0

fun <T> testListValue(list: List<T>) {
    println()
    println("[$testListValueCounter] testListValue(List)")
    testListValueCounter++
    var counter = 0
    for (value in list) {
        println("List[$counter] = ${value}")
        counter++
    }
}

var testListPtrExtCounter = 0

fun <T> @Ptr List<T>.testListPtrExt() {
    println()
    println("[$testListPtrExtCounter] List.testListPtrExt()")
    testListPtrExtCounter++
    var counter = 0
    for (value in this) {
        println("List[$counter] = ${value}")
        counter++
    }
}

var testListValueExtCounter = 0

fun <T> List<T>.testListValueExt() {
    println()
    println("[$testListValueExtCounter] List.testListValueExt()")
    testListValueExtCounter++
    var counter = 0
    for (value in this) {
        println("List[$counter] = ${value}")
        counter++
    }
}