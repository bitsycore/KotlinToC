package StringTemplateTest

data class Vec2(val x: Float, val y: Float)

fun main() {
    val name = "World"
    val age = 42

    val t1 = "Hello, $name!"
    if (t1 != "Hello, World!") error("FAIL t1: $t1")
    println(t1)

    val t2 = "Age: $age"
    if (t2 != "Age: 42") error("FAIL t2: $t2")
    println(t2)

    val t3 = "Next year: ${age + 1}"
    if (t3 != "Next year: 43") error("FAIL t3: $t3")
    println(t3)

    val v = Vec2(3.0f, 4.0f)
    println("Position: $v")

    val s = "hello"
    val t5 = "Length of '$s' is ${s.length}"
    if (t5 != "Length of 'hello' is 5") error("FAIL t5: $t5")
    println(t5)

    val a = 10
    val b = 20
    val t6 = "$a + $b = ${a + b}"
    if (t6 != "10 + 20 = 30") error("FAIL t6: $t6")
    println(t6)

    println("done")
}
