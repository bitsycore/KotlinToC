/** A text made by a template and an object of a class handed to the same generic function: the text stays a text there. */
fun <T> check(expected: T, actual: T) {
	if (expected != actual) println("differ: <$expected> <$actual>") else println("same: <$expected>")
}

fun main() {
	val c = 'x'
	check(c.toString(), "x")
	check(Pair('a', 'b'), Pair('a', 'b'))
}
