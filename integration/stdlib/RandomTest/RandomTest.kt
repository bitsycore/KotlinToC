package RandomTest

fun testNextInt() {
    println("--- nextInt ---")
    println("nextInt() = ${Random.nextInt()}")
    println("nextInt(100) = ${Random.nextInt(100)}")
}

fun testNextIntBetween() {
    println("--- nextIntBetween ---")
    for (i in 0 until 5) {
        val v = Random.nextInt(10, 20)
        println("nextIntBetween(10, 20) = $v")
        if (v < 10 || v >= 20) error("FAIL nextInt(10,20) out of range: $v")
    }
}

fun testNextLong() {
    println("--- nextLong ---")
    println("nextLong() = ${Random.nextLong()}")
    println("nextLong(1000L) = ${Random.nextLong(1000L)}")
}

fun testNextLongBetween() {
    println("--- nextLongBetween ---")
    println("nextLongBetween(0L, 100L) = ${Random.nextLong(0L, 100L)}")
}

fun testNextFloat() {
    println("--- nextFloat ---")
    for (i in 0 until 5) {
        println("nextFloat() = ${Random.nextFloat()}")
    }
}

fun testNextDouble() {
    println("--- nextDouble ---")
    for (i in 0 until 5) {
        println("nextDouble() = ${Random.nextDouble()}")
    }
}

fun testNextDoubleBetween() {
    println("--- nextDoubleBetween ---")
    for (i in 0 until 5) {
        println("nextDoubleBetween(5.0, 10.0) = ${Random.nextDouble(5.0, 10.0)}")
    }
}

fun testNextBoolean() {
    println("--- nextBoolean ---")
    var t = 0
    var f = 0
    for (i in 0 until 20) {
        if (Random.nextBoolean()) t++ else f++
    }
    println("true=$t false=$f")
    if (t == 0 || f == 0) error("FAIL nextBoolean never produced both values t=$t f=$f")
}

fun main() {

    testNextInt()
    testNextIntBetween()
    testNextLong()
    testNextLongBetween()
    testNextFloat()
    testNextDouble()
    testNextDoubleBetween()
    testNextBoolean()
    println("ALL OK")
}
