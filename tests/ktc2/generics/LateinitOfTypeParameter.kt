// A `lateinit` local of a type parameter that stands for a number here (as kotlinx.serialization's
// tests write a custom serializer): it is not set until assigned, and reading it before says so.

package ktc2test.generics.lateinitoftypeparameter

import kotlin.test.Test

fun <T : Any> firstOf(inValues: List<T>): T {
	lateinit var vFound: T
	for (vValue in inValues) {
		vFound = vValue
		break
	}
	return vFound
}

@Test
fun main() {
	println(firstOf(listOf(3, 4)))
	println(firstOf(listOf("a")))
	println(firstOf(listOf(2.5)))
	try {
		firstOf(emptyList<Int>())
	} catch (vError: UninitializedPropertyAccessException) {
		println("caught: ${vError.message}")
	}
}
