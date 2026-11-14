/** Small things found by the tests of the standard library: an object that delegates to a local, a built-in companion as a value, the iterator of an array of numbers. */
fun main() {
	val coll = listOf("foo", "bar", "abc")
	val wrapped = object : List<String> by coll {}
	println(wrapped.takeLast(2))
	println(wrapped.size)
	val i = Int
	println(i === Int)
	val any: Any = Char
	println(any.toString().isNotEmpty())
	println(intArrayOf(1, 2, 3).iterator().asSequence().toList())
	val it = charArrayOf('a').iterator()
	println(it.nextChar())
}
