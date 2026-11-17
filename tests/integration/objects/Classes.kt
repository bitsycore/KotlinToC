package objects

class Point(val x: Int, val y: Int) {
	fun plus(other: Point): Point = Point(x + other.x, y + other.y)
	fun manhattan(): Int = (if (x < 0) -x else x) + (if (y < 0) -y else y)
	override fun toString(): String = "($x, $y)"
}

class Counter(start: Int, private val step: Int = 1) {
	var value = start
		private set
	var bumps = 0

	init {
		println("counter from $start")
	}

	fun bump(): Counter {
		value += step
		bumps++
		return this
	}
}

class Node(val value: Int, var next: Node?)

class Pair2(val first: Point, var second: Point?) {
	val label: String

	init {
		label = "pair of $first"
	}

	constructor(only: Point) : this(only, null) {
		println("single $only")
	}
}

class Account(val owner: String, balance: Long) {
	var balance = balance
		set(value) {
			println("$owner: $field -> $value")
			field = value
		}
	val isEmpty get() = balance == 0L
}

fun length(list: Node?): Int = if (list == null) 0 else 1 + length(list.next)

fun build(n: Int): Node? {
	var vHead: Node? = null
	for (vI in n downTo 1) vHead = Node(vI, vHead)
	return vHead
}

fun sum(list: Node?): Int {
	var vTotal = 0
	var vCurrent = list
	while (vCurrent != null) {
		vTotal += vCurrent.value
		vCurrent = vCurrent.next
	}
	return vTotal
}

fun reverse(list: Node?): Node? {
	var vResult: Node? = null
	var vCurrent = list
	while (vCurrent != null) {
		val vNext = vCurrent.next
		vCurrent.next = vResult
		vResult = vCurrent
		vCurrent = vNext
	}
	return vResult
}

fun farthest(a: Point, b: Point): Point = if (a.manhattan() >= b.manhattan()) a else b

fun main() {
	val vA = Point(1, 2)
	val vB = vA.plus(Point(10, -20))
	println(vB)
	println(vB.manhattan())
	println("a=$vA b=$vB far=${farthest(vA, vB)}")
	println(vA.plus(vA).plus(vA).x)

	val vCounter = Counter(5, 3)
	vCounter.bump().bump()
	println("${vCounter.value} ${vCounter.bumps}")
	println(Counter(0).bump().value)

	val vList = build(5)
	println("${length(vList)} ${sum(vList)}")
	val vBack = reverse(vList)
	println(vBack!!.value)
	println(vBack.next?.value)
	println(build(0)?.value)
	println(vBack.next!!.next!!.next!!.next!!.next?.value ?: -1)

	val vPair = Pair2(vA)
	println(vPair.label)
	println(vPair.second)
	vPair.second = vB
	vPair.second = vPair.first
	println(vPair.second)

	val vAccount = Account("ann", 10)
	vAccount.balance = vAccount.balance - 10
	println(vAccount.isEmpty)

	// Aliasing: both names see the mutation.
	val vAlias = vCounter
	vAlias.bump()
	println(vCounter.value)
	println(vAlias === vCounter)
	println(vA === Point(1, 2))
	println(vA == vA)

	// Objects created and dropped in a loop.
	var vLast = Point(0, 0)
	for (vI in 1..1000) vLast = vLast.plus(Point(vI, 1))
	println(vLast)
}
