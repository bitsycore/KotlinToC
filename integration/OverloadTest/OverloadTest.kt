package OverloadTest

object Calc {
    fun add(x: Int, y: Int): Int = x + y
    fun add(x: Double, y: Double): Double = x + y
    fun add(x: Int): Int = x

    fun greet(): String = "hello"
    fun greet(name: String): String = "hello $name"
}

class Counter() {
    var n: Int = 0

    fun inc() { n = n + 1 }
    fun inc(by: Int) { n = n + by }
    fun inc(by: Int, times: Int) {
        for (i in 0 until times) { n = n + by }
    }
    fun count(): Int = n
}

fun doIt(): Int = 3
fun doIt(x: Int): Int = 3 + x

fun main() {
    val a = Calc.add(2, 3)
    val b = Calc.add(2.5, 3.5)
    val single = Calc.add(42)
    val g1 = Calc.greet()
    val g2 = Calc.greet("World")

    if (a != 5) error("FAIL add 2+3=$a")
    if (b != 6.0) error("FAIL add 2.5+3.5=$b")
    if (single != 42) error("FAIL add single=$single")
    if (g1 != "hello") error("FAIL greet=$g1")
    if (g2 != "hello World") error("FAIL greet=$g2")
    println("Object overloads OK")

    val ctr = Counter()
    ctr.inc()
    if (ctr.count() != 1) error("FAIL inc=$ctr.count()")
    ctr.inc(5)
    if (ctr.count() != 6) error("FAIL inc by=$ctr.count()")
    ctr.inc(2, 3)
    if (ctr.count() != 12) error("FAIL inc by times=$ctr.count()")
    println("Class overloads OK")

    val t1 = doIt()
    if (t1 != 3) error("FAIL doIt=$t1")
    val t2 = doIt(42)
    if (t2 != 45) error("FAIL doIt 42=$t2")
    println("Top-level overloads OK")

    println("ALL OK")
}
