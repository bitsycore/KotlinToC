package WhileDoTest

fun testWhileBasic() {
    var n = 0
    while (n < 5) { n++ }
    if (n != 5) error("FAIL whileBasic: $n")
    println("whileBasic ok: $n")
}

fun testWhileProduct() {
    var n = 1
    var product = 1
    while (n <= 5) {
        product *= n
        n++
    }
    if (product != 120) error("FAIL whileProduct: $product")
    println("whileProduct ok: $product")
}

fun testDoWhileBasic() {
    var n = 0
    do {
        n++
    } while (n < 5)
    if (n != 5) error("FAIL doWhileBasic: $n")
    println("doWhileBasic ok: $n")
}

fun testDoWhileAlwaysOnce() {
    // do-while runs the body at least once even when condition is false from the start
    var ran = 0
    do {
        ran++
    } while (false)
    if (ran != 1) error("FAIL doWhileOnce: $ran")
    println("doWhileOnce ok: $ran")
}

fun testWhileBoolFlag() {
    var running = true
    var count = 0
    while (running) {
        count++
        if (count >= 3) running = false
    }
    if (count != 3) error("FAIL whileBool: $count")
    println("whileBool ok: $count")
}

fun testNestedWhile() {
    var total = 0
    var i = 0
    while (i < 4) {
        var j = 0
        while (j < 4) {
            total++
            j++
        }
        i++
    }
    if (total != 16) error("FAIL nestedWhile: $total")
    println("nestedWhile ok: $total")
}

fun testDoWhileAccumulate() {
    var sum = 0
    var x = 1
    do {
        sum += x
        x++
    } while (x <= 10)
    if (sum != 55) error("FAIL doWhileAccumulate: $sum")
    println("doWhileAccumulate ok: $sum")
}

fun main() {
    testWhileBasic()
    testWhileProduct()
    testDoWhileBasic()
    testDoWhileAlwaysOnce()
    testWhileBoolFlag()
    testNestedWhile()
    testDoWhileAccumulate()
    println("done")
}
