package SmartCastTest

fun main() {
    val x: Int? = 42
    if (x != null) {
        val doubled = x * 2
        if (doubled != 84) error("FAIL doubled: $doubled")
        println("doubled: $doubled")
    }
    if (x == null) {
        println("is null")
    } else {
        println("value = $x")
    }

    val s: String? = "hello"
    if (s != null) {
        if (s.length != 5) error("FAIL length: ${s.length}")
        println("length: ${s.length}")
    }

    val arr = intArrayOf(1, 2, 3)
    if (arr is Array<Int>) {
        println("arr is IntArray")
    } else {
        error("FAIL arr should be Array<Int>")
    }
    if (arr !is Array<Float>) {
        println("arr is not FloatArray")
    } else {
        error("FAIL arr should not be Array<Float>")
    }
    if (arr.size <= 0) error("FAIL arr.size=${arr.size}")
    println("has items: ${arr.size > 0}")

    println("done")
}
