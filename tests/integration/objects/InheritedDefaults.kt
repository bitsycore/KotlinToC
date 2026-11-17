/** A default argument that names an earlier parameter, of a member called through an object that inherits it. */
open class Codec(val name: String) {
	fun decode(source: CharSequence, startIndex: Int = 0, endIndex: Int = source.length): String = source.substring(startIndex, endIndex) + name
	companion object Default : Codec("d")
}

fun main() {
	println(Codec.decode("abc"))
	for (c in listOf(Codec.Default, Codec("x"))) {
		try { println(c.decode("xyz")) } catch (e: IllegalArgumentException) { println("no") }
	}
}
