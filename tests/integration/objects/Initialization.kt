package objects

object Registry {
	val items = ArrayList<String>()
	init { println("Registry created") }
	fun add(s: String) { items.add(s) }
}

object Late {
	val stamp: Int = Registry.items.size
	init { println("Late created with $stamp") }
}

enum class Level(val weight: Int) {
	LOW(1), HIGH(10);
	init { println("level $name") }
}

class Widget {
	companion object {
		val count: Int = compute()
		fun compute(): Int { println("companion created"); return 3 }
	}
}

inline fun repeatTwice(crossinline action: (Int) -> Unit): () -> Unit = { action(1); action(2) }

inline fun <T> List<T>.eachDeferred(crossinline action: (T) -> Unit): List<() -> Unit> = map { item -> { action(item) } }

fun apply(f: (Any) -> String, v: Int): String = f(v)

fun interface Shaper { fun shape(x: Int): Int }

fun useShaper(s: Shaper): Int = s.shape(5)

fun main() {
	println("main starts")
	Registry.add("a")
	Registry.add("b")
	println(Late.stamp)
	println(Level.HIGH.weight)
	println(Widget.count)
	var sum = 0
	val twice = repeatTwice { sum += it }
	twice(); twice()
	println(sum)
	val deferred = listOf(1, 2, 3).eachDeferred { sum += it * 10 }
	deferred.forEach { it() }
	println(sum)
	val describe: (Any?) -> String = { "<$it>" }
	println(apply(describe, 7))
	val doubler: (Int) -> Int = { it * 2 }
	println(useShaper(Shaper(doubler)))
	println(useShaper(doubler))
	val cmp: (String, String) -> Int = { a, b -> a.length - b.length }
	println(listOf("ccc", "a", "bb").sortedWith(Comparator(cmp)))
}
