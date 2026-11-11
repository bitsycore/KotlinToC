package ArrayAliasTest

fun testByteArray() {
    val lit = byteArrayOf(10, 20, 30)
    println("byteArrayOf: ${lit[1]}")
    if (lit[1] != 20.toByte()) error("Error")
}

fun testShortArray() {
    val lit = shortArrayOf(10, 20, 30)
    println("shortArrayOf: ${lit[1]}")
    if (lit[1] != 20.toShort()) error("Error")
}

fun testIntArray() {
    val lit = intArrayOf(10, 20, 30)
    println("intArrayOf: ${lit[1]}")
    if (lit[1] != 20) error("Error")
}

fun testLongArray() {
    val lit = longArrayOf(10L, 20L, 30L)
    println("longArrayOf: ${lit[1]}")
    if (lit[1] != 20L) error("Error")
}

fun testFloatArray() {
    val lit = floatArrayOf(1.5f, 2.5f, 3.5f)
    println("floatArrayOf: ${lit[1]}")
    if (lit[1] != 2.5f) error("Error")
}

fun testDoubleArray() {
    val lit = doubleArrayOf(1.1, 2.2, 3.3)
    println("doubleArrayOf: ${lit[1]}")
    if (lit[1] != 2.2) error("Error")
}

fun testBooleanArray() {
    val lit = booleanArrayOf(true, false, true)
    println("booleanArrayOf: ${lit[1]}")
    if (lit[1]) error("Error")
}

fun testCharArray() {
    val lit = charArrayOf('a', 'b', 'c')
    println("charArrayOf: ${lit[1]}")
    if (lit[1] != 'b') error("Error")
}

fun testUByteArray() {
    val lit = ubyteArrayOf(10u, 20u, 30u)
    println("ubyteArrayOf: ${lit[1]}")
    if (lit[1] != 20.toUByte()) error("Error")
}

fun testUShortArray() {
    val lit = ushortArrayOf(10u, 20u, 30u)
    println("ushortArrayOf: ${lit[1]}")
    if (lit[1] != 20.toUShort()) error("Error")
}

fun testUIntArray() {
    val lit = uintArrayOf(10u, 20u, 30u)
    println("uintArrayOf: ${lit[1]}")
    if (lit[1] != 20u) error("Error")
}

fun testULongArray() {
    val lit = ulongArrayOf(10UL, 20UL, 30UL)
    println("ulongArrayOf: ${lit[1]}")
    if (lit[1] != 20UL) error("Error")
}

fun testStringArray() {
    val lit = arrayOf("hello", "world")
    println("arrayOf<String>: ${lit[0]}")
}

fun main() {
    testByteArray()
    testShortArray()
    testIntArray()
    testLongArray()
    testFloatArray()
    testDoubleArray()
    testBooleanArray()
    testCharArray()
    testUByteArray()
    testUShortArray()
    testUIntArray()
    testULongArray()
    testStringArray()
    println("ALL OK")
}
