package ExtensionFunctionTest

class Box(var value: Int)

fun Box.double(): Int { return value * 2 }
fun Box.reset() { value = 0 }

fun main() {
    val b = Box(21)
    val d = b.double()
    if (d != 42) error("FAIL double: $d")
    println("Box double: $d")
    b.reset()
    println("after reset: ${b.double()}")
    println("done")
}
