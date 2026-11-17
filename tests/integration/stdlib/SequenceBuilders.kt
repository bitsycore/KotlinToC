// `sequence { }` and `iterator { }` as ordinary code writes them: `yield`, `yieldAll`, loops,
// `try`/`finally`, helpers that are `suspend` extensions of `SequenceScope`, sequences that are
// left before their end, and the library functions written with them.

class Resource(val name: String) {
	fun close() = println("close $name")
}

fun fibonacci() = sequence {
	var a = 0L
	var b = 1L
	while (true) {
		yield(a)
		val next = a + b
		a = b
		b = next
	}
}

suspend fun SequenceScope<Int>.twice(value: Int) {
	yield(value)
	yield(value)
}

suspend fun SequenceScope<String>.words(text: String) {
	for (word in text.split(" ")) {
		if (word.isEmpty()) continue
		yield(word.uppercase())
	}
}

fun guarded(name: String) = sequence {
	val resource = Resource(name)
	try {
		for (i in 1..3) {
			println("$name yields $i")
			yield(i)
		}
	} finally {
		resource.close()
	}
}

fun tree(depth: Int, label: String): Sequence<String> = sequence {
	yield(label)
	if (depth > 0) {
		yieldAll(tree(depth - 1, label + "L"))
		yieldAll(tree(depth - 1, label + "R"))
	}
}

class Node(val value: Int, val children: List<Node>) {
	fun walk(): Sequence<Int> = sequence {
		yield(value)
		for (child in children) yieldAll(child.walk())
	}
}

fun failing() = sequence {
	yield(1)
	throw IllegalArgumentException("broken")
}

fun main() {
	println(fibonacci().take(15).toList())
	println(fibonacci().map { it * 2 }.filter { it % 3 == 0L }.take(5).toList())
	println(sequence { twice(1); twice(2); yield(3) }.toList())
	println(sequence { words("a quick  brown fox") }.joinToString(","))
	// Left while it waits inside a helper: the helper's frame holds the scope.
	println(sequence { twice(1); twice(2); twice(3) }.take(3).toList())
	println(sequence { words("left in the middle") }.first())
	println(guarded("full").toList())
	println(guarded("first").first())
	println(guarded("two").take(2).toList())
	println(tree(2, "*").toList())
	println(Node(1, listOf(Node(2, listOf(Node(3, emptyList()))), Node(4, emptyList()))).walk().toList())
	println(sequence { yieldAll(listOf(1, 2)); yieldAll(emptyList()); yieldAll(sequenceOf(3, 4)); yieldAll(listOf(5).iterator()) }.toList())

	val iterator = iterator {
		yield("x")
		yield("y")
	}
	while (iterator.hasNext()) println(iterator.next())
	println(runCatching { iterator.next() }.exceptionOrNull()?.let { it::class.simpleName })

	val empty = sequence<Int> { }
	println(empty.toList())
	println(runCatching { failing().toList() }.exceptionOrNull()?.message)
	val partial = failing().iterator()
	println(partial.next())
	println(runCatching { partial.hasNext() }.exceptionOrNull()?.message)

	val once = sequence { yield(listOf(1, 2)); yield(listOf(3)) }
	println(once.flatten().toList())
	println(once.count())

	// Library functions that are written with `sequence { }`.
	val numbers = generateSequence(1) { it + 1 }
	println(numbers.windowed(3, 2).take(3).toList())
	println(numbers.chunked(4).take(2).toList())
	println(numbers.take(5).scan(0) { acc, x -> acc + x }.toList())
	println(numbers.take(5).zipWithNext().toList())
	println(numbers.take(4).runningReduce { acc, x -> acc * x }.toList())

	var counter = 0
	val lazy = sequence {
		while (true) {
			counter++
			yield(counter)
		}
	}
	println(lazy.take(3).toList())
	println("counter $counter")
	println(lazy.elementAt(4))
	println("counter $counter")
}
