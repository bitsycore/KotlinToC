package math

data class Point(val x: Int, val y: Int)

fun add(a: Int, b: Int): Int {
    return a + b
}

fun distance(p: Point): Double {
    return ((p.x * p.x + p.y * p.y).toDouble())
}

fun clamp(value: Int, low: Int, high: Int): Int {
    return if (value < low) low else if (value > high) high else value
}
