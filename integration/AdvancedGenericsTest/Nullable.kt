package advancedgenerics

// ===================================================
// MARK: Concrete Nullable
// ===================================================

var testPtrNullableCounter = 0

fun <T> testPtrNullable(list: @Ptr ArrayList<T>?) {
    println()
    println("[$testPtrNullableCounter] testPtrNullable(ArrayList)")
    testPtrNullableCounter++
    if (list == null) {
        println("ArrayList is null")
        return
    }
    var counter = 0
    for (value in list) {
        println("ArrayList[$counter] = ${value}")
        counter++
    }
}

var testValueNullableCounter = 0

fun <T> testValueNullable(list: ArrayList<T>?) {
    println()
    println("[$testValueNullableCounter] testValueNullable(ArrayList)")
    testValueNullableCounter++
    if (list == null) {
        println("ArrayList is null")
        return
    }
    var counter = 0
    for (value in list) {
        println("ArrayList[$counter] = ${value}")
        counter++
    }
}

var testPtrExtNullableCounter = 0

fun <T> @Ptr ArrayList<T>?.testPtrExtNullable() {
    println()
    println("[$testPtrExtNullableCounter] ArrayList.testPtrExtNullable()")
    testPtrExtNullableCounter++
    if (this == null) {
        println("ArrayList is null")
        return
    }
    var counter = 0
    for (value in this) {
        println("ArrayList[$counter] = ${value}")
        counter++
    }
}

var testValueExtNullableCounter = 0

fun <T> ArrayList<T>?.testValueExtNullable() {
    println()
    println("[$testValueExtNullableCounter] ArrayList.testValueExtNullable()")
    testValueExtNullableCounter++
    if (this == null) {
        println("ArrayList is null")
        return
    }
    var counter = 0
    for (value in this) {
        println("ArrayList[$counter] = ${value}")
        counter++
    }
}

// ===================================================
// MARK: Interface Nullable
// ===================================================

var testListPtrNullableCounter = 0

fun <T> testListPtrNullable(list: @Ptr List<T>?) {
    println()
    println("[$testListPtrNullableCounter] testListPtrNullable(List)")
    testListPtrNullableCounter++
    if (list == null) {
        println("List is null")
        return
    }
    var counter = 0
    for (value in list) {
        println("List[$counter] = ${value}")
        counter++
    }
}

var testListValueNullableCounter = 0

fun <T> testListValueNullable(list: List<T>?) {
    println()
    println("[$testListValueNullableCounter] testListValueNullable(List)")
    testListValueNullableCounter++
    if (list == null) {
        println("List is null")
        return
    }
    var counter = 0
    for (value in list) {
        println("List[$counter] = ${value}")
        counter++
    }
}

var testListPtrExtNullableCounter = 0

fun <T> @Ptr List<T>?.testListPtrExtNullable() {
    println()
    println("[$testListPtrExtNullableCounter] List.testListPtrExtNullable()")
    testListPtrExtNullableCounter++
    if (this == null) {
        println("List is null")
        return
    }
    var counter = 0
    for (value in this) {
        println("List[$counter] = ${value}")
        counter++
    }
}

var testListValueExtNullableCounter = 0

fun <T> List<T>?.testListValueExtNullable() {
    println()
    println("[$testListValueExtNullableCounter] List.testListValueExtNullable()")
    testListValueExtNullableCounter++
    if (this == null) {
        println("List is null")
        return
    }
    var counter = 0
    for (value in this) {
        println("List[$counter] = ${value}")
        counter++
    }
}