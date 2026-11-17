// Labels and non-local returns as ordinary code writes them: `return` from inside `forEach`,
// `let`, `run`, nested inline lambdas, `return@label` from lambdas, labeled `break` and
// `continue` in nested loops, returns through `try`/`finally`, and inline functions of the program.

inline fun <T> repeatEach(items: List<T>, action: (T) -> Unit) {
	for (item in items) action(item)
}

inline fun measure(name: String, block: () -> Int): Int {
	println("start $name")
	try {
		return block()
	} finally {
		println("end $name")
	}
}

fun firstNegative(values: List<Int>): Int? {
	values.forEach {
		if (it < 0) return it
	}
	return null
}

fun findPair(values: List<Int>, sum: Int): Pair<Int, Int>? {
	values.forEachIndexed { i, a ->
		values.drop(i + 1).forEach { b ->
			if (a + b == sum) return a to b
		}
	}
	return null
}

fun labeledLambda(values: List<Int>): List<Int> {
	val out = mutableListOf<Int>()
	values.forEach each@{
		if (it % 2 == 0) return@each
		out += it
	}
	values.forEach {
		if (it > 3) return@forEach
		out += it * 100
	}
	return out
}

fun anonymousFunction(values: List<Int>): List<Int> = values.mapNotNull(fun(v): Int? {
	if (v == 0) return null
	return 100 / v
})

fun grid(): List<String> {
	val found = mutableListOf<String>()
	outer@ for (i in 1..4) {
		inner@ for (j in 1..4) {
			if (j == i) continue@outer
			if (i * j > 6) break@outer
			if (j == 3) continue@inner
			found += "$i$j"
		}
	}
	return found
}

fun whileLabels(): Int {
	var count = 0
	var i = 0
	loop@ while (true) {
		i++
		var j = 0
		do {
			j++
			if (j == 2) continue
			if (i == 5) break@loop
			count += j
		} while (j < 3)
	}
	return count
}

fun withFinally(values: List<String>): String {
	val log = StringBuilder()
	try {
		values.forEach {
			log.append(it)
			if (it == "stop") return log.append("!").toString()
		}
		log.append(".")
	} finally {
		log.append("[finally]")
		println("finally sees $log")
	}
	return log.toString()
}

fun nestedScopes(text: String?): String {
	text?.let { t ->
		t.run {
			if (isEmpty()) return "empty"
			with(StringBuilder()) {
				append(uppercase())
				if (length > 3) return "long ${toString()}"
			}
		}
		return "short $t"
	}
	return "null"
}

fun measured(values: List<Int>): Int = measure("sum") {
	repeatEach(values) {
		if (it > 100) return -1
	}
	values.sum()
}

fun firstLongWord(text: String): String {
	text.split(" ").asSequence().forEach { if (it.length > 4) return it }
	return "-"
}

fun lambdaResult(values: List<Int>): List<String> = values.map {
	if (it < 0) return@map "neg"
	if (it == 0) "zero" else "pos"
}

fun searchMap(map: Map<String, List<Int>>, target: Int): String {
	for ((key, list) in map) {
		list.forEach { if (it == target) return key }
	}
	return "none"
}

fun main() {
	println(firstNegative(listOf(3, 4, -2, -5)))
	println(firstNegative(listOf(1, 2)))
	println(findPair(listOf(1, 5, 3, 7), 10))
	println(findPair(listOf(1, 2), 10))
	println(labeledLambda(listOf(1, 2, 3, 4, 5)))
	println(anonymousFunction(listOf(5, 0, 20)))
	println(grid())
	println(whileLabels())
	println(withFinally(listOf("a", "stop", "b")))
	println(withFinally(listOf("a", "b")))
	println(nestedScopes(null))
	println(nestedScopes(""))
	println(nestedScopes("ab"))
	println(nestedScopes("abcdef"))
	println(measured(listOf(1, 2, 3)))
	println(measured(listOf(1, 200, 3)))
	println(firstLongWord("a quick brown fox"))
	println(firstLongWord("a b"))
	println(lambdaResult(listOf(-1, 0, 1)))
	println(searchMap(linkedMapOf("x" to listOf(1, 2), "y" to listOf(3, 4)), 4))
	println(searchMap(mapOf("x" to listOf(1)), 9))
	val result = run {
		listOf(1, 2, 3).forEach { if (it == 2) return@run "found two" }
		"no two"
	}
	println(result)
}
