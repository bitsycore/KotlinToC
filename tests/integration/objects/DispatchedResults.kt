// An object that comes back from a call through an interface, where the class behind it is
// not known: built in the caller's frame when it stays there, and on the heap when it gets out.

package dispatched

// ==================
// MARK: Iterators
// ==================

/** A list of its own kind: the numbers from [first], [size] of them. */
class Counting(private val first: Int, override val size: Int) : AbstractList<Int>() {
	override fun get(index: Int): Int = first + index
}

/** Goes over a list whose class the caller chooses. */
fun total(values: List<Int>): Int {
	var vTotal = 0
	for (vValue in values) vTotal += vValue
	return vTotal
}

fun join(values: Collection<String>): String {
	val vText = StringBuilder()
	for (vValue in values) vText.append(vValue).append('.')
	return vText.toString()
}

fun totalOfMap(values: Map<String, Int>): Int {
	var vTotal = 0
	for ((vKey, vValue) in values) vTotal += vKey.length * vValue
	return vTotal
}

/** Two iterators over the same list at once. */
fun pairs(values: List<Int>): Int {
	var vCount = 0
	for (vA in values) for (vB in values) if (vA < vB) vCount++
	return vCount
}

/** The iterator gets out: it must outlive this function. */
fun iteratorOf(values: List<Int>): Iterator<Int> = values.iterator()

/** Keeps an iterator, and so the list it goes over. */
class Reader(values: List<String>) {
	private val mIterator = values.iterator()

	fun next(): String = if (mIterator.hasNext()) mIterator.next() else "-"
}

var gKept: Iterator<Int>? = null

fun keep(values: Iterable<Int>) {
	gKept = values.iterator()
}

/** A list that only lives here, gone over by a function that takes any list. */
fun localList(count: Int): Int {
	val vValues = ArrayList<Int>()
	for (vI in 0 until count) vValues.add(vI * 3)
	return total(vValues) + pairs(vValues)
}

/** A list that only lives here, except that a reader of it is handed out. */
fun readerOfLocal(): Reader {
	val vWords = ArrayList<String>()
	vWords.add("one")
	vWords.add("two")
	return Reader(vWords)
}

fun iteratorOfLocal(): Iterator<Int> {
	val vValues = ArrayList<Int>()
	vValues.add(7)
	vValues.add(8)
	return iteratorOf(vValues)
}

/** Leaves the loop with an exception while the iterator is in the frame. */
fun firstNegative(values: List<Int>): Int {
	for (vValue in values) if (vValue < 0) throw IllegalStateException("negative: $vValue")
	return 0
}

// ==================
// MARK: Other results
// ==================

class Box(val width: Int, val height: Int) {
	val area: Int get() = width * height
}

interface Shape {
	/** A new box each time for some shapes, the same one for others. */
	fun bounds(): Box

	fun scaled(factor: Int): Shape
}

class Square(val side: Int) : Shape {
	override fun bounds(): Box = Box(side, side)

	override fun scaled(factor: Int): Shape = Square(side * factor)
}

class Fixed(private val mBox: Box) : Shape {
	override fun bounds(): Box = mBox

	override fun scaled(factor: Int): Shape = this
}

class Failing : Shape {
	override fun bounds(): Box = throw UnsupportedOperationException("no bounds")

	override fun scaled(factor: Int): Shape = if (factor > 1) Square(factor) else throw IllegalArgumentException("factor $factor")
}

fun area(shape: Shape): Int {
	val vBox = shape.bounds()
	return vBox.area
}

fun scaledArea(shape: Shape, factor: Int): Int {
	val vScaled = shape.scaled(factor)
	val vBox = vScaled.bounds()
	return vBox.width + vBox.height
}

val gBoxes = ArrayList<Box>()

/** The box gets out through a list. */
fun remember(shape: Shape): Int {
	val vBox = shape.bounds()
	gBoxes.add(vBox)
	return vBox.area
}

/** The box gets out as the result, or not at all. */
fun largest(shapes: List<Shape>): Box? {
	var vBest: Box? = null
	for (vShape in shapes) {
		val vBox = vShape.bounds()
		if (vBest == null || vBox.area > vBest.area) vBest = vBox
	}
	return vBest
}

fun main() {
	val vGrown = ArrayList<Int>()
	for (vI in 1..5) vGrown.add(vI * vI)
	val vLists = listOf(vGrown, listOf(4, 5, 6), emptyList(), listOf(9), Counting(10, 4), vGrown.subList(1, 3), vGrown.reversed())
	for (vList in vLists) println("${total(vList)} ${pairs(vList)}")

	println(join(listOf("a", "b")) + " " + join(setOf("c", "d", "c")) + " " + join(LinkedHashSet<String>()) + " " + join(mapOf("k" to 1, "l" to 2).keys))
	println(totalOfMap(mapOf("one" to 1, "three" to 3)) + totalOfMap(HashMap()) + totalOfMap(LinkedHashMap<String, Int>().also { it["xy"] = 5 }))

	var vSum = 0
	for (vRound in 0 until 1000) vSum += localList(vRound % 7)
	println(vSum)

	val vIterator = iteratorOf(vGrown)
	println("${vIterator.next()} ${vIterator.next()}")
	val vOfLocal = iteratorOfLocal()
	println("${vOfLocal.next()} ${vOfLocal.next()} ${vOfLocal.hasNext()}")
	val vReader = readerOfLocal()
	println("${vReader.next()} ${vReader.next()} ${vReader.next()}")
	keep(Counting(100, 2))
	keep(vGrown)
	println("${gKept!!.next()} ${gKept!!.next()}")
	gKept = null

	println(firstNegative(vGrown))
	try {
		firstNegative(listOf(1, -2, 3))
	} catch (vError: IllegalStateException) {
		println(vError.message)
	}
	try {
		vGrown.add(0, -1)
		firstNegative(vGrown)
	} catch (vError: IllegalStateException) {
		println(vError.message)
	}

	val vShapes = listOf(Square(3), Fixed(Box(2, 5)), Square(1))
	for (vShape in vShapes) println("${area(vShape)} ${scaledArea(vShape, 4)} ${remember(vShape)}")
	println(gBoxes.sumOf { it.area })
	println(largest(vShapes)?.area)
	println(largest(emptyList()))
	for (vShape in listOf(Failing(), Square(2))) {
		try {
			println(area(vShape))
		} catch (vError: UnsupportedOperationException) {
			println(vError.message)
		}
		for (vFactor in 1..2) {
			try {
				println(scaledArea(vShape, vFactor))
			} catch (vError: RuntimeException) {
				println(vError.message)
			}
		}
	}
	var vAreas = 0L
	for (vRound in 0 until 2000) for (vShape in vShapes) vAreas += area(vShape) + scaledArea(vShape, vRound % 3 + 1)
	println(vAreas)
	gBoxes.clear()
}
