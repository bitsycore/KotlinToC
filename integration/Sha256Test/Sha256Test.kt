package Sha256Test

fun checkHex(hash: @Size(32) ByteArray, expected: String): Boolean {
    val hex = "0123456789abcdef"
    for (i in 0 until 32) {
        val v = hash[i].toInt() and 0xff
        val hi = (v ushr 4)
        val lo = (v and 0xf)
        val eh = expected[i * 2].toInt()
        val el = expected[i * 2 + 1].toInt()
        if (hex[hi] != eh || hex[lo] != el) return false
    }
    return true
}

fun printHash(hash: @Size(32) ByteArray) {
    val hex = "0123456789abcdef"
    for (i in 0 until 32) {
        val v = hash[i].toInt() and 0xff
        c.putchar(hex[v ushr 4].toInt())
        c.putchar(hex[v and 0xf].toInt())
    }
    c.putchar('\n'.toInt())
}

fun testEmpty() {
    println("--- empty string ---")
    val ctx = Sha256.new()
    val hash = ctx.finalizeHash()
    printHash(hash)
    if (!checkHex(hash, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")) {
        println("FAIL")
        error("Hash failed")
    }
    println("OK")
}

fun testAbc() {
    println("--- abc ---")
    val buf = byteArrayOf('a'.toByte(), 'b'.toByte(), 'c'.toByte())
    val ctx = Sha256.new()
    ctx.update(buf.ptr(), 0, buf.size)
    val hash = ctx.finalizeHash()
    printHash(hash)
    if (!checkHex(hash, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")) {
        println("FAIL")
        error("Hash failed")
    }
    println("OK")
}

fun testHelloWorld() {
    println("--- hello world ---")
    val buf = byteArrayOf(
        'h'.toByte(), 'e'.toByte(), 'l'.toByte(), 'l'.toByte(), 'o'.toByte(),
        ' '.toByte(),
        'w'.toByte(), 'o'.toByte(), 'r'.toByte(), 'l'.toByte(), 'd'.toByte()
    )
    val ctx = Sha256.new()
    ctx.update(buf.ptr(), 0, buf.size)
    val hash = ctx.finalizeHash()
    printHash(hash)
    if (!checkHex(hash, "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9")) {
        println("FAIL")
        error("Hash failed")
    }
    println("OK")
}

fun testLongMessage() {
    println("--- 64 bytes 'a' ---")
    val buf = ByteArray(64)
    for (i in 0 until 64) {
        buf[i] = 'a'.toByte()
    }
    val ctx = Sha256.new()
    ctx.update(buf.ptr(), 0, buf.size)
    val hash = ctx.finalizeHash()
    printHash(hash)
    if (!checkHex(hash, "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb")) {
        println("FAIL")
        error("Hash failed")
    }
    println("OK")
}

fun testLongMessage2() {
    println("--- 64 bytes 'a' v2 ---")
    val buf = ByteArray(64) { 'a'.toByte() }
    val hash = Sha256.digest(buf.ptr())
    printHash(hash)
    if (!checkHex(hash, "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb")) {
        println("FAIL")
        error("Hash failed")
    }
    println("OK")
}

fun testPartialUpdates() {
    println("--- partial updates ---")
    val ctx = Sha256.new()
    val buf1 = byteArrayOf('a'.toByte(), 'b'.toByte())
    ctx.update(buf1.ptr())
    val buf2 = byteArrayOf('c'.toByte())
    ctx.update(buf2.ptr())
    val hash = ctx.finalizeHash()
    printHash(hash)
    if (!checkHex(hash, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")) {
        println("FAIL")
        error("Hash failed")
    }
    println("OK")
}

fun main() {
    testEmpty()
    testAbc()
    testHelloWorld()
    testLongMessage()
    testPartialUpdates()
    testLongMessage2()
    println("ALL OK")
}
