// A bad index or range on a string is an exception that can be caught, also when the string
// is seen as a CharSequence, and with the message the JVM gives.

fun at(inText: CharSequence, inIndex: Int): Char = inText[inIndex]

fun part(inText: CharSequence, inFrom: Int, inTo: Int): CharSequence = inText.subSequence(inFrom, inTo)

fun attempt(inWhat: String, inBlock: () -> Any?) {
	try {
		println("$inWhat: ${inBlock()}")
	} catch (inError: IndexOutOfBoundsException) {
		println("$inWhat: ${inError.message}")
	}
}

fun main() {
	val vText = "abcdef"
	val vSequence: CharSequence = vText
	attempt("substring") { vText.substring(2, 4) }
	attempt("substring beyond the end") { vText.substring(2, 9) }
	attempt("substring from beyond the end") { vText.substring(8) }
	attempt("substring backwards") { vText.substring(4, 2) }
	attempt("substring from a negative index") { vText.substring(-1, 2) }
	attempt("subSequence") { vText.subSequence(1, 3) }
	attempt("subSequence beyond the end") { vText.subSequence(1, 30) }
	attempt("character of a CharSequence") { at(vSequence, 1) }
	attempt("character beyond a CharSequence") { at(vSequence, 10) }
	attempt("part of a CharSequence") { part(vSequence, 2, 5) }
	attempt("part beyond a CharSequence") { part(vSequence, 2, 50) }
	attempt("character of a StringBuilder") { at(StringBuilder("xyz"), 2) }
	attempt("take and drop") { vText.take(20) + vText.drop(20) }
	println("end")
}
