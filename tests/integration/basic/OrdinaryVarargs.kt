// `vararg` as ordinary code writes it: spread of arrays and of several arrays with values between,
// arrays of numbers and of objects, named arguments after a vararg, a vararg that is not last,
// generic varargs, varargs passed on, defaults, and the library functions that take them.

fun sum(vararg numbers: Int): Int = numbers.sum()

fun joinAll(separator: String = ",", vararg parts: String) = parts.joinToString(separator)

fun <T> listOfNonNull(vararg items: T?): List<T> = items.filterNotNull()

fun describe(vararg values: Any?) = values.joinToString { "${it?.let { v -> v::class.simpleName }}=$it" }

fun prefixed(vararg words: String, prefix: String) = words.map { prefix + it }

fun forward(vararg numbers: Int) = sum(*numbers) + sum(*numbers, 100)

fun <T : Comparable<T>> largest(first: T, vararg rest: T): T = rest.fold(first) { a, b -> if (b > a) b else a }

fun longs(vararg values: Long) = values.size to values.sum()

fun doubles(vararg values: Double) = values.average()

fun chars(vararg values: Char) = String(values)

fun booleans(vararg values: Boolean) = values.count { it }

fun counts(vararg values: Int): String = "size ${values.size}, class ${values::class.simpleName}"

fun matrix(vararg rows: IntArray) = rows.joinToString("|") { it.joinToString(",") }

fun <T> spreadGeneric(vararg items: T): List<T> = listOf(*items)

class Builder {
	private val parts = ArrayList<String>()
	fun add(vararg items: String): Builder {
		parts.addAll(items)
		return this
	}
	override fun toString() = parts.toString()
}

fun main() {
	val numbers = intArrayOf(1, 2, 3)
	println(sum())
	println(sum(5))
	println(sum(*numbers))
	println(sum(0, *numbers, 10, *numbers))
	println(forward(1, 2))
	println(joinAll(parts = arrayOf("a", "b")))
	println(joinAll("-", "x", "y", "z"))
	val words = arrayOf("one", "two")
	println(joinAll("/", *words, "three", *words))
	println(listOfNonNull(1, null, 3, null))
	println(listOfNonNull<String>())
	println(describe(1, "s", 2.0, null, 'c', mutableListOf(1)))
	println(prefixed("a", "b", prefix = ">"))
	println(prefixed(*words, prefix = "#"))
	println(largest(3, 9, 2))
	println(largest("pear", "apple", "zebra", "kiwi"))
	println(largest(1.5))
	println(longs(1L, 2L, Long.MAX_VALUE / 2))
	println(doubles(1.0, 2.0, 4.5))
	println(chars('h', 'i', *charArrayOf('!', '?')))
	println(booleans(true, false, true))
	println(counts(*IntArray(4)))
	println(matrix(intArrayOf(1, 2), intArrayOf(), intArrayOf(3)))
	println(spreadGeneric(*arrayOf(1, 2), 3))
	println(spreadGeneric<Any?>(null, "x"))
	println(Builder().add("a").add(*words).add())

	val copy = arrayOf(*words)
	copy[0] = "changed"
	println(words.toList() + copy.toList())
	println(listOf(*words, "end"))
	println(mutableListOf(0, *numbers.toTypedArray()))
	println(setOf(*arrayOf(1, 1, 2)))
	println(mapOf(*arrayOf("a" to 1, "b" to 2)))
	println(arrayListOf(*words))
	println(maxOf(1, 5, 3, 2))
	println(minOf(4.0, *doubleArrayOf(2.5, 3.0)))
	println(intArrayOf(*numbers, 4).toList())
	println(String.format2("%s-%s", "a", "b"))
	println("%d items".format2(3))
}

/** Stands for a formatting function that a program writes itself, taking `vararg Any?`. */
fun String.Companion.format2(pattern: String, vararg args: Any?): String = pattern.format2(*args)

fun String.format2(vararg args: Any?): String {
	var index = 0
	return Regex("%[sd]").replace(this) { args[index++].toString() }
}
