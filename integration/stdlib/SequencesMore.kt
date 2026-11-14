/** A local class in generic code is of the instantiation of the code around it. */
fun <T> pairsOf(source: Iterable<T>): Iterator<Pair<Int, T>> = object : Iterator<Pair<Int, T>> {
	val iterator = source.iterator()
	var index = 0
	override fun hasNext(): Boolean = iterator.hasNext()
	override fun next(): Pair<Int, T> = Pair(index++, iterator.next())
}

class Holder<T>(val item: T) {
	fun wrapped(): Iterator<Pair<T, T>> = object : Iterator<Pair<T, T>> {
		var left = 1
		override fun hasNext(): Boolean = left > 0
		override fun next(): Pair<T, T> = Pair(item, item).also { left-- }
	}

	fun both(): Pair<T, T> {
		class Local(val left: T, val right: T) {
			fun pair(): Pair<T, T> = Pair(left, right)
		}
		return Local(item, item).pair()
	}
}

fun fibonacci(): Sequence<Int> =
	generateSequence(Pair(0, 1), { Pair(it.second, it.first + it.second) }).map { it.first * 1 }

fun main() {
	val s = sequenceOf(5, 6, 7)
	println(s.withIndex().filter { it.index != 1 }.toList())
	println(s.withIndex().map { it.value }.toList())
	println(s.withIndex().filter { it.index != 1 }.map { it.value + 1 }.toList())
	println(fibonacci().filterIndexed { index, _ -> index % 2 == 1 }.take(5).toList())
	println(fibonacci().withIndex().filter { it.index > 3 }.take(3).joinToString { it.value.toString() })
	val data = sequenceOf(null, "foo", null, "bar")
	println(data.filter { it == null || it == "foo" }.toList())
	println(data.filterNot { it == null }.toList())

	var sum = 0
	for (pair in pairsOf(listOf(10, 20, 30))) sum += pair.first * pair.second
	println(sum)
	for (pair in pairsOf(listOf("a", "b"))) println(pair.second + pair.first)
	println(Holder(3).both().first + Holder(4L).both().second)
	println(Holder('x').wrapped().next().first.code + Holder(2.5).wrapped().next().second)
	println(Holder("s").both())
}
