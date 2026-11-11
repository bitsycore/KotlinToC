package generics

class Box<T>(var value: T) {
	fun get(): T = value
	fun replace(next: T): T {
		val vOld = value
		value = next
		return vOld
	}
	override fun toString(): String = "Box($value)"
}

data class Pair2<A, B>(val first: A, val second: B) {
	fun swapped(): Pair2<B, A> = Pair2(second, first)
}

class Stack<T> {
	private var items = arrayOfNulls<Any?>(4)
	var size = 0
		private set

	fun push(item: T) {
		if (size == items.size) {
			val vBigger = arrayOfNulls<Any?>(size * 2)
			for (vI in 0 until size) vBigger[vI] = items[vI]
			items = vBigger
		}
		items[size] = item
		size++
	}

	@Suppress("UNCHECKED_CAST")
	fun pop(): T {
		size--
		val vItem = items[size] as T
		items[size] = null
		return vItem
	}

	fun isEmpty(): Boolean = size == 0
}

class Node<T>(val value: T, var next: Node<T>?)

class Chain<T> {
	private var head: Node<T>? = null
	var count = 0

	fun add(value: T): Chain<T> {
		head = Node(value, head)
		count++
		return this
	}

	fun first(): T? = head?.value

	fun contains(value: T): Boolean {
		var vCurrent = head
		while (vCurrent != null) {
			if (vCurrent.value == value) return true
			vCurrent = vCurrent.next
		}
		return false
	}
}

interface Source<T> {
	fun next(): T
	fun hasNext(): Boolean
}

class Countdown(private var from: Int) : Source<Int> {
	override fun next(): Int = from--
	override fun hasNext(): Boolean = from > 0
}

class Words(private val words: Array<String>) : Source<String> {
	private var index = 0
	override fun next(): String = words[index++]
	override fun hasNext(): Boolean = index < words.size
}

open class Shape(val name: String) {
	open fun area(): Double = 0.0
}

class Square(val side: Double) : Shape("square") {
	override fun area(): Double = side * side
}

fun <T> identity(value: T): T = value

fun <T> firstOr(values: Array<T>, fallback: T): T = if (values.size > 0) values[0] else fallback

fun <A, B> pairOf(a: A, b: B): Pair2<A, B> = Pair2(a, b)

fun <T> drain(source: Source<T>): Int {
	var vCount = 0
	while (source.hasNext()) {
		source.next()
		vCount++
	}
	return vCount
}

fun sumAll(source: Source<Int>): Int {
	var vTotal = 0
	while (source.hasNext()) vTotal += source.next()
	return vTotal
}

fun <T : Shape> biggest(a: T, b: T): T = if (a.area() >= b.area()) a else b

fun <T : Comparable<T>> maxOf(a: T, b: T): T = if (a.compareTo(b) >= 0) a else b

class Version(val major: Int, val minor: Int) : Comparable<Version> {
	override fun compareTo(other: Version): Int = if (major != other.major) major - other.major else minor - other.minor
	override fun toString(): String = "$major.$minor"
}

fun <T> describe(value: T): String = "<$value>"

fun <T> countNonNull(values: Array<T?>): Int {
	var vCount = 0
	for (vValue in values) {
		if (vValue != null) vCount++
	}
	return vCount
}

fun main() {
	// Generic class with primitive and reference arguments
	val vInts = Box(41)
	println(vInts.get() + 1)
	println(vInts.replace(7))
	println(vInts)
	val vText = Box("hi")
	println(vText.get().length)
	vText.value = vText.value + "!"
	println(vText)
	val vNested = Box(Box(2.5))
	println(vNested.get().get() * 2)
	println(vNested)
	val vMaybe = Box<String?>(null)
	println(vMaybe.get())
	val vLong = Box(3000000000L)
	println(vLong.get() + 1)

	// Several type parameters
	val vPair = pairOf(1, "one")
	println(vPair)
	println(vPair.swapped())
	println(vPair.swapped().first.length + vPair.first)
	println(vPair == Pair2(1, "one"))
	val (vNumber, vName) = pairOf(2.5, 'c')
	println("$vNumber $vName")

	// A generic container built on an array
	val vStack = Stack<Int>()
	for (vI in 1..10) vStack.push(vI * vI)
	var vSum = 0
	while (!vStack.isEmpty()) vSum += vStack.pop()
	println(vSum)
	val vNames = Stack<String>()
	vNames.push("a")
	vNames.push("b")
	println(vNames.pop() + vNames.pop() + vNames.size)

	val vChain = Chain<String>().add("x").add("y")
	println("${vChain.count} ${vChain.first()} ${vChain.contains("x")} ${vChain.contains("z")}")
	val vDoubles = Chain<Double>().add(1.5)
	println("${vDoubles.first()} ${vDoubles.contains(1.5)} ${Chain<Int>().first()}")

	// Generic functions and interfaces
	println(identity(5) + identity(6))
	println(identity("text").length)
	println(firstOr(arrayOf("a", "b"), "none") + firstOr(arrayOf(), "none"))
	println(firstOr(arrayOf(3, 4), 0))
	println(drain(Countdown(4)))
	println(drain(Words(arrayOf("p", "q"))))
	println(sumAll(Countdown(5)))
	val vSource: Source<String> = Words(arrayOf("only"))
	println(vSource.next())

	// Bounds
	println(biggest(Square(2.0), Square(3.0)).side)
	println(maxOf(3, 9))
	println(maxOf("pear", "apple"))
	println(maxOf(Version(1, 4), Version(1, 10)))
	println(describe(12) + describe("s") + describe(null) + describe(Version(2, 0)) + describe(1.5))
	println(countNonNull(arrayOf("a", null, "c")))
	println(countNonNull(arrayOf<Int?>(1, null)))
}
