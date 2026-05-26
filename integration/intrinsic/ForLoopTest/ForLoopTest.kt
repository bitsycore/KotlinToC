package ForLoopTest

fun testRangeInclusive() {
    println("--- 0..5 ---")
    var count = 0
    for (i in 0..5) {
        println(i)
        count++
    }
    if (count != 6) error("FAIL 0..5 count=$count")
}

fun testRangeUntil() {
    println("--- 0 until 5 ---")
    var count = 0
    for (i in 0 until 5) {
        println(i)
        count++
    }
    if (count != 5) error("FAIL 0 until 5 count=$count")
}

fun testDownTo() {
    println("--- 5 downTo 0 ---")
    var count = 0
    for (i in 5 downTo 0) {
        println(i)
        count++
    }
    if (count != 6) error("FAIL 5 downTo 0 count=$count")
}

fun testStep() {
    println("--- 0..10 step 2 ---")
    var count1 = 0
    for (i in 0..10 step 2) {
        println(i)
        count1++
    }
    if (count1 != 6) error("FAIL 0..10 step 2 count=$count1")
    println("--- 10 downTo 0 step 3 ---")
    var count2 = 0
    for (i in 10 downTo 0 step 3) {
        println(i)
        count2++
    }
    if (count2 != 4) error("FAIL 10 downTo 0 step 3 count=$count2")
}

fun testForInArray() {
    val arr = intArrayOf(100, 200, 300)
    var sum = 0
    for (x in arr) {
        println(x)
        sum += x
    }
    if (sum != 600) error("FAIL array sum=$sum")
}

fun main() {
    testRangeInclusive()
    testRangeUntil()
    testDownTo()
    testStep()
    testForInArray()
    println("done")
}
