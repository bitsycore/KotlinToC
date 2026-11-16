package test.text

// What StringEncodingTest leaves to the platform. The JVM puts one replacement character for the three
// bytes of half a pair, and `?` for half a pair it has to encode; ktc2 does as the JVM does.

internal val surrogateCodePointDecoding: String = "�"

internal val surrogateCharEncoding: ByteArray = byteArrayOf(0x3F)
