// Ordinary generic code: classes over numbers and objects seen through one type, stars, casts.

class Box<T>(var value: T) {
	fun get(): T = value
	override fun toString(): String = "Box($value)"
}

open class Shape(val name: String) { override fun toString(): String = "Shape($name)" }
class Circle(val radius: Double) : Shape("circle")

class Stack<T> {
	private val mItems = ArrayList<T>()
	val size: Int get() = mItems.size
	fun push(inItem: T) { mItems += inItem }
	fun pop(): T = mItems.removeAt(mItems.size - 1)
	fun peek(): T? = mItems.lastOrNull()
	operator fun iterator(): Iterator<T> = mItems.iterator()
}

interface Source<out T> { fun next(): T }
class Counter(private var mAt: Int) : Source<Int> { override fun next(): Int = mAt++ }
class Words(private val mWords: List<String>) : Source<String> {
	private var mAt = 0
	override fun next(): String = mWords[mAt++ % mWords.size]
}

fun <T> describe(inBox: Box<T>): String = "${inBox.value} ${inBox.get()} $inBox"
fun anyBox(inBox: Box<*>): String = "star ${inBox.value} ${inBox.get()} ${inBox.value is Int} ${inBox.value is String}"
fun <T : Comparable<T>> largest(inValues: List<T>): T { var vBest = inValues[0]; for (vValue in inValues) if (vValue > vBest) vBest = vValue; return vBest }
fun <T> firstOr(inValues: List<T>, inDefault: T): T = if (inValues.isEmpty()) inDefault else inValues[0]
fun <A, B> swap(inPair: Pair<A, B>): Pair<B, A> = Pair(inPair.second, inPair.first)
inline fun <reified T> countOf(inValues: List<Any?>): Int = inValues.count { it is T }
fun <T> take(inSource: Source<T>, inCount: Int): List<T> = List(inCount) { inSource.next() }
fun sum(vararg inValues: Int): Int = inValues.sum()
fun <T> listOfAll(vararg inValues: T): List<T> = inValues.toList()

fun main() {
	val vInt = Box(5)
	val vText = Box("five")
	val vReal = Box(2.5)
	val vNull = Box<Int?>(null)
	val vShape = Box<Shape>(Circle(1.5))
	println("${describe(vInt)} | ${describe(vText)} | ${describe(vReal)} | ${describe(vNull)}")
	val vBoxes: List<Box<*>> = listOf(vInt, vText, vReal, vNull, vShape)
	for (vBox in vBoxes) println(anyBox(vBox))
	val vAny: List<Any> = listOf(vInt, vText, "text", 5, vShape)
	println(vAny.map { it is Box<*> })
	println(vAny.filterIsInstance<Box<*>>().map { it.value.toString() })
	println((vAny[0] as Box<*>).value)
	println((vAny[1] as Box<*>).get())
	@Suppress("UNCHECKED_CAST")
	val vBack = vAny[0] as Box<Int>
	vBack.value += 10
	println("${vBack.value} ${vInt.value} ${vInt.get() + 1}")
	vInt.value = 7
	vText.value += "!"
	println("$vInt $vText ${vShape.value.name} ${(vShape.value as Circle).radius}")

	val vStack = Stack<Int>()
	for (vIndex in 1..5) vStack.push(vIndex * vIndex)
	println("${vStack.pop()} ${vStack.peek()} ${vStack.size}")
	var vTotal = 0
	for (vItem in vStack) vTotal += vItem
	println(vTotal)
	val vNames = Stack<String>()
	vNames.push("a"); vNames.push("b")
	println("${vNames.pop()}${vNames.pop()} ${vNames.peek()}")
	val vStacks: List<Stack<*>> = listOf(vStack, vNames)
	println(vStacks.map { it.size })

	println("${largest(listOf(3, 9, 2))} ${largest(listOf("pear", "apple"))} ${largest(listOf(1.5, -2.0))} ${largest(listOf('a', 'z'))}")
	println("${firstOr(emptyList(), 4)} ${firstOr(listOf("x"), "y")} ${firstOr<Int?>(emptyList(), null)}")
	println("${swap(Pair(1, "one"))} ${swap(Pair(2.5, 'c'))} ${swap(swap(Pair(1L, true)))}")
	val vMixed = listOf<Any?>(1, "two", 3.0, null, 4, 'c', 5L, listOf(1), Box(1))
	println("${countOf<Int>(vMixed)} ${countOf<String>(vMixed)} ${countOf<Number>(vMixed)} ${countOf<Any>(vMixed)} ${countOf<List<*>>(vMixed)} ${countOf<Box<*>>(vMixed)} ${countOf<Comparable<*>>(vMixed)}")
	val vSources: List<Source<Any>> = listOf(Counter(10), Words(listOf("x", "y")))
	println(vSources.map { take(it, 3) })
	println("${take(Counter(1), 4).sum()} ${take(Words(listOf("a", "b", "c")), 4).joinToString("")}")
	println("${sum()} ${sum(1)} ${sum(1, 2, 3)} ${sum(*intArrayOf(4, 5))} ${listOfAll(1, "a", null)} ${listOfAll<Int>()}")

	val vPairs = listOf(1 to "one", 2 to "two")
	val (vNumbers, vWords) = vPairs.unzip()
	println("$vNumbers $vWords ${vPairs.toMap()[2]} ${Triple(1, 2.0, "3")} ${Triple(1, 2.0, "3").second + 1}")
	val vByLength: Map<Int, List<String>> = listOf("a", "bb", "cc", "d").groupBy { it.length }
	println("$vByLength ${vByLength[2]?.size} ${vByLength[3]}")
	val vOptional: Box<Box<Int>?> = Box(Box(1))
	println("${vOptional.value?.value} ${Box<Box<Int>?>(null).value?.value}")
	val vFunctions: List<(Int) -> Any> = listOf({ it + 1 }, { "n$it" }, { it * 0.5 }, ::Box)
	println(vFunctions.map { it(4) })
}
