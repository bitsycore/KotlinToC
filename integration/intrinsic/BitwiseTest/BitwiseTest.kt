package BitwiseTest

fun testAnd() {
    val a = 0b1010
    val b = 0b1100
    val r = a and b  // 0b1000 = 8
    if (r != 8) fatalError("FAIL and: $r")
    println("and ok: $r")
}

fun testOr() {
    val a = 0b1010
    val b = 0b0101
    val r = a or b  // 0b1111 = 15
    if (r != 15) fatalError("FAIL or: $r")
    println("or ok: $r")
}

fun testXor() {
    val a = 0b1010
    val b = 0b1100
    val r = a xor b  // 0b0110 = 6
    if (r != 6) fatalError("FAIL xor: $r")
    println("xor ok: $r")
}

fun testInv() {
    val a = 0b1010  // = 10
    val r = a.inv() and 0xFF  // bitwise NOT, keep low byte: 0b11110101 = 245
    if (r != 245) fatalError("FAIL inv: $r")
    println("inv ok: $r")
}

fun testShl() {
    val a = 1
    val r = a shl 4  // 16
    if (r != 16) fatalError("FAIL shl: $r")
    val r2 = a shl 8  // 256
    if (r2 != 256) fatalError("FAIL shl2: $r2")
    println("shl ok: $r, $r2")
}

fun testShr() {
    val a = 64
    val r = a shr 3  // 8
    if (r != 8) fatalError("FAIL shr: $r")
    println("shr ok: $r")
}

fun testUshr() {
    val a = -1           // all bits set in Int (0xFFFFFFFF)
    val r = a ushr 28    // fills with zeros from left → 0b00001111 = 15
    if (r != 15) fatalError("FAIL ushr: $r")
    println("ushr ok: $r")
}

fun testBitFlags() {
    val READ  = 1
    val WRITE = 2
    val EXEC  = 4

    var perms = READ or WRITE
    if (perms and READ  == 0) fatalError("FAIL flags: READ not set")
    if (perms and WRITE == 0) fatalError("FAIL flags: WRITE not set")
    if (perms and EXEC  != 0) fatalError("FAIL flags: EXEC should not be set")

    perms = perms or EXEC
    if (perms and EXEC == 0) fatalError("FAIL flags: EXEC not set after OR")

    perms = perms xor WRITE    // toggle off WRITE
    if (perms and WRITE != 0) fatalError("FAIL flags: WRITE should be cleared")
    println("bitFlags ok: perms=$perms")
}

fun testCompoundBitOps() {
    var x = 0b11111111  // 255
    x = x and 0b11110000  // mask lower nibble → 0b11110000 = 240
    if (x != 240) fatalError("FAIL compound1: $x")
    x = x or  0b00001111  // set lower nibble  → 0b11111111 = 255
    if (x != 255) fatalError("FAIL compound2: $x")
    x = x xor 0b10101010  // toggle bits       → 0b01010101 = 85
    if (x != 85) fatalError("FAIL compound3: $x")
    println("compoundBitOps ok: $x")
}

fun main() {
    testAnd()
    testOr()
    testXor()
    testInv()
    testShl()
    testShr()
    testUshr()
    testBitFlags()
    testCompoundBitOps()
    println("done")
}
