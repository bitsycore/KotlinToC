package Utf8Test

fun main() {
    var ok = true

    // ── ASCII: runeLen == byteLen ─────────────────────────────────────
    val ascii = "Hello"
    val asciiByteLen = ascii.len
    val asciiRuneLen = ascii.runeLen
    if (asciiByteLen != 5) { println("FAIL: ascii byteLen"); ok = false }
    else println("OK: ascii byteLen = $asciiByteLen")
    if (asciiRuneLen != 5) { println("FAIL: ascii runeLen"); ok = false }
    else println("OK: ascii runeLen = $asciiRuneLen")

    // ── 2-byte UTF-8 char (é = U+00E9 = 2 bytes) ──────────────────────
    val cafe = "caf\u00E9"   // 4 chars, 5 bytes (c=1,a=1,f=1,é=2)
    val cafeByteLen = cafe.len
    val cafeRuneLen = cafe.runeLen
    if (cafeByteLen != 5) { println("FAIL: café byteLen"); ok = false }
    else println("OK: café byteLen = $cafeByteLen")
    if (cafeRuneLen != 4) { println("FAIL: café runeLen"); ok = false }
    else println("OK: café runeLen = $cafeRuneLen")

    // ── 3-byte UTF-8 char (世 = U+4E16 = 3 bytes) ─────────────────────
    val helloWorld = "Hello\u4E16\u754C"  // "Hello世界" = 7 runes, 5+3+3=11 bytes
    val hwByteLen = helloWorld.len
    val hwRuneLen = helloWorld.runeLen
    if (hwByteLen != 11) { println("FAIL: helloWorld byteLen"); ok = false }
    else println("OK: helloWorld byteLen = $hwByteLen")
    if (hwRuneLen != 7) { println("FAIL: helloWorld runeLen"); ok = false }
    else println("OK: helloWorld runeLen = $hwRuneLen")

    // ── runeAt: decode individual code points ─────────────────────────
    // "caf\u00E9": c(0)=0x63, a(1)=0x61, f(2)=0x66, é(3)=0xE9 at byte offset 3
    val r0 = cafe.runeAt(0)
    val r3 = cafe.runeAt(3)
    if (r0 != 0x63) { println("FAIL: cafe.runeAt(0)"); ok = false }
    else println("OK: cafe.runeAt(0) = 0x63")
    if (r3 != 0xE9) { println("FAIL: cafe.runeAt(3)"); ok = false }
    else println("OK: cafe.runeAt(3) = 0xE9")

    // ── Byte indexing still works ─────────────────────────────────────
    val b0 = cafe[0]
    if (b0 != 'c') { println("FAIL: cafe[0]"); ok = false }
    else println("OK: cafe[0] = 'c'")

    if (ok) {
        println("ALL OK")
    } else {
        println("SOME FAILED")
    }
}
