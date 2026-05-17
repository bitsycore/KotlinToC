package NullabilityTest

fun maybeInt(n: Int): Int? {
    if (n > 0) {
        return n
    }
    return null
}

fun main() {
    val x: Int? = null
    println("null == null = ${x == null}")
    if (x != null) error("FAIL x should be null")
    val y: Int? = 42
    println("42 != null = ${y != null}")
    if (y == null) error("FAIL y should not be null")

    val v = x ?: 99
    println("elvis: $v")
    if (v != 99) error("FAIL elvis: $v")

    val a = maybeInt(10)
    if (a != null) {
        if (a != 10) error("FAIL maybeInt(10): $a")
        println("maybeInt(10) = $a")
    } else {
        error("FAIL maybeInt(10) is null")
    }

    val b = maybeInt(0)
    if (b != null) error("FAIL maybeInt(0) should be null")
    println("maybeInt(0) = null")

    val s: String? = "hello"
    if (s != null) {
        if (s.length != 5) error("FAIL string length")
        println("length = ${s.length}")
    } else {
        error("FAIL s is null")
    }

    println("done")
}
