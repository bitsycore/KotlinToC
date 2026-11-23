// An array cast to an array of a narrower element type through an unchecked cast (`values as Array<Data?>`). Kotlin/Native
// erases the element type: the cast holds, and an element that is not a Data throws a ClassCastException where it is
// read as one. ktc2 keeps the element type and read it as one without a check (a String read as a Data); the elements
// are checked at the cast now (the owner's decision, 2026-11-23). The JVM's arrays know their type: there every cast of an
// Object[] to Data[] throws. `.expected` is what Kotlin/Native prints.

package arraycastelements

class Data(val x: Int)

@Suppress("UNCHECKED_CAST")
fun readFirst(values: Array<Any?>): String = try {
	val vData = values as Array<Data?>
	"read ${vData.firstOrNull()?.x}"
} catch (e: ClassCastException) {
	"ClassCastException"
}

fun main() {
	println(readFirst(arrayOf("text")))
	println(readFirst(arrayOf(Data(4), null)))
	println(readFirst(arrayOf(null, null)))
	println(readFirst(arrayOf()))
}
