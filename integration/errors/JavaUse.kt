// A program has no JDK: a use of java.* is refused, with the line.

fun main() {
	val vList = java.util.LinkedList<Int>()
	vList.add(1)
	println(vList.size)
}
