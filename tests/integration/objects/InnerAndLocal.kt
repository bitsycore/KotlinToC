// Inner and local classes as ordinary code writes them: inner classes that read and change their
// outer object, `this@Outer`, inner classes of generic classes, local classes and functions that
// capture variables, anonymous objects with state, local classes that extend and implement.

class Bank(val name: String) {
	private var total = 0
	// The names only: a list of the accounts, each of which holds the bank, would be a cycle.
	private val accounts = ArrayList<String>()

	inner class Account(val owner: String) {
		var balance = 0
			private set

		fun deposit(amount: Int): Account {
			balance += amount
			total += amount
			return this
		}

		fun bankName() = this@Bank.name
		override fun toString() = "$owner@$name: $balance"
	}

	fun open(owner: String) = Account(owner).also { accounts += it.toString() }
	fun report() = "$name holds $total in ${accounts.size} accounts: $accounts"
}

class Matrix<T>(private val rows: Int, private val cols: Int, init: (Int, Int) -> T) {
	private val cells = MutableList(rows * cols) { init(it / cols, it % cols) }

	inner class Row(val index: Int) : Iterable<T> {
		operator fun get(col: Int): T = cells[index * cols + col]
		override fun iterator(): Iterator<T> = object : Iterator<T> {
			var col = 0
			override fun hasNext() = col < cols
			override fun next(): T = this@Row[col++]
		}
	}

	fun row(index: Int) = Row(index)
	fun rows() = (0 until rows).map { Row(it) }
}

class Outer {
	private val secret = "outer secret"
	var calls = 0

	inner class Middle {
		val label = "middle"
		inner class Deepest {
			fun reveal(): String {
				calls++
				return "$secret via $label"
			}
		}
	}

	class Nested {
		fun hello() = "nested has no outer"
	}
}

interface Greeter {
	fun greet(who: String): String
}

fun makeGreeters(prefixes: List<String>): List<Greeter> {
	class Prefixed(val prefix: String) : Greeter {
		override fun greet(who: String) = "$prefix, $who"
	}
	return prefixes.map { Prefixed(it) }
}

fun counterPair(): Pair<() -> Int, () -> Int> {
	var count = 0
	class Step(val by: Int) {
		fun apply(): Int {
			count += by
			return count
		}
	}
	val one = Step(1)
	val ten = Step(10)
	return Pair({ one.apply() }, { ten.apply() })
}

fun fibonacciMemo(n: Int): Long {
	val memo = HashMap<Int, Long>()
	fun fib(k: Int): Long {
		if (k < 2) return k.toLong()
		memo[k]?.let { return it }
		val value = fib(k - 1) + fib(k - 2)
		memo[k] = value
		return value
	}
	return fib(n)
}

abstract class Shape(val name: String) {
	abstract fun area(): Double
	override fun toString() = "$name(${area()})"
}

fun shapes(scale: Double): List<Shape> {
	class Circle(val r: Double) : Shape("circle") {
		override fun area() = 3.0 * r * r * scale
	}
	val square = object : Shape("square") {
		val side = 2.0
		override fun area() = side * side * scale
	}
	return listOf(Circle(1.0), square, Circle(2.0))
}

fun eventLog(): MutableList<String> {
	val log = mutableListOf<String>()
	val listener = object {
		var seen = 0
		fun on(event: String) {
			seen++
			log += "$seen:$event"
		}
	}
	listener.on("start")
	listener.on("stop")
	log += "seen ${listener.seen}"
	return log
}

fun <T : Comparable<T>> sortedWithLocal(items: List<T>): List<T> {
	class Holder(val value: T) : Comparable<Holder> {
		override fun compareTo(other: Holder) = other.value.compareTo(value)
	}
	return items.map { Holder(it) }.sorted().map { it.value }
}

class Tree(val value: Int) {
	val children = mutableListOf<Tree>()

	inner class Walker {
		val visited = mutableListOf<Int>()
		fun walk(): Walker {
			fun visit(t: Tree) {
				visited += t.value
				t.children.forEach { visit(it) }
			}
			visit(this@Tree)
			return this
		}
	}
}

fun main() {
	val bank = Bank("north")
	bank.open("ann").deposit(10).deposit(5)
	val bob = bank.open("bob")
	bob.deposit(7)
	println(bob.bankName())
	println(bank.report())
	val other = Bank("south")
	val acc = other.Account("cy")
	acc.deposit(1)
	println(acc)
	println(other.report())

	val m = Matrix(2, 3) { r, c -> r * 10 + c }
	println(m.row(1)[2])
	println(m.rows().map { it.toList() })
	val words = Matrix(2, 2) { r, c -> "w$r$c" }
	println(words.rows().joinToString(" | ") { row -> row.joinToString(",") })

	val outer = Outer()
	val deepest = outer.Middle().Deepest()
	println(deepest.reveal())
	println(deepest.reveal())
	println(outer.calls)
	println(Outer.Nested().hello())

	println(makeGreeters(listOf("hi", "hello")).map { it.greet("you") })
	val (one, ten) = counterPair()
	println(listOf(one(), ten(), one(), ten()))
	println(fibonacciMemo(60))
	println(shapes(1.5))
	println(eventLog())
	println(sortedWithLocal(listOf(3, 1, 2)))
	println(sortedWithLocal(listOf("b", "c", "a")))

	val tree = Tree(1)
	val two = Tree(2)
	tree.children += two
	two.children += Tree(3)
	tree.children += Tree(4)
	println(tree.Walker().walk().visited)
}
