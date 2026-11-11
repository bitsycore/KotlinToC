package WhenTest

enum class Color { RED, GREEN, BLUE }

fun testWhenExpr() {
    val x = 3
    val desc = when (x) {
        1 -> "one"
        2 -> "two"
        3 -> "three"
        else -> "many"
    }
    println(desc)
    if (desc != "three") error("FAIL when expr")
}

fun testWhenStatement() {
    val x = 2
    var hit = false
    when (x) {
        1 -> println("is one")
        2 -> { println("is two"); hit = true }
        else -> println("is other")
    }
    if (!hit) error("FAIL when statement")
}

fun testWhenCondition() {
    val x = 15
    var hit = false
    when {
        x < 0 -> println("negative")
        x == 0 -> println("zero")
        x < 10 -> println("small")
        else -> { println("large"); hit = true }
    }
    if (!hit) error("FAIL when condition")
}

fun testWhenEnum() {
    val color = Color.GREEN
    var hit = false
    when (color) {
        Color.RED -> println("Red!")
        Color.GREEN -> { println("Green!"); hit = true }
        Color.BLUE -> println("Blue!")
    }
    if (!hit) error("FAIL when enum")
}

fun main() {
    testWhenExpr()
    testWhenStatement()
    testWhenCondition()
    testWhenEnum()
    println("ALL OK")
}
