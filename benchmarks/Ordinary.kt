// Benchmark: ordinary code, whose objects mostly do not outlive the function that makes them:
// local lists, small holders, pairs, lambdas, loops over lists that came in as parameters.

/** A point of the plane. */
data class Point(val x: Int, val y: Int) {
	operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)
}

/** The smallest rectangle around some points. */
class Bounds {
	var left = Int.MAX_VALUE
	var top = Int.MAX_VALUE
	var right = Int.MIN_VALUE
	var bottom = Int.MIN_VALUE

	fun add(point: Point) {
		if (point.x < left) left = point.x
		if (point.y < top) top = point.y
		if (point.x > right) right = point.x
		if (point.y > bottom) bottom = point.y
	}

	val area: Long get() = (right - left).toLong() * (bottom - top)
}

/** A running count and total. */
class Accumulator {
	var count = 0
	var total = 0L

	fun add(value: Int) {
		count++
		total += value
	}
}

// ==================
// MARK: Local objects
// ==================

/** Points made, added up and thrown away: none leaves the function. */
fun walk(steps: Int, seed: Int): Long {
	var vPlace = Point(seed, -seed)
	val vBounds = Bounds()
	for (vI in 0 until steps) {
		val vStep = Point((vI * 7 + seed) % 5 - 2, (vI * 3 + seed) % 7 - 3)
		vPlace = vPlace + vStep
		vBounds.add(vPlace)
	}
	return vBounds.area + vPlace.x + vPlace.y
}

/** Two results at once, through a pair. */
fun divide(value: Int, by: Int): Pair<Int, Int> = Pair(value / by, value % by)

// ==================
// MARK: Local lists
// ==================

/** A list filled, gone over and dropped. */
fun digits(value: Int): Int {
	val vDigits = ArrayList<Int>()
	var vRest = value
	while (vRest > 0) {
		vDigits.add(vRest % 10)
		vRest /= 10
	}
	var vSum = 0
	for (vDigit in vDigits) vSum += vDigit
	return vSum * vDigits.size
}

/** The sum of a list, whatever class of list it is given. */
fun total(values: List<Int>): Long {
	var vTotal = 0L
	for (vValue in values) vTotal += vValue
	return vTotal
}

/** The same with an index. */
fun weighted(values: List<Int>): Long {
	var vTotal = 0L
	for (vIndex in values.indices) vTotal += values[vIndex].toLong() * (vIndex + 1)
	return vTotal
}

// ==================
// MARK: Lambdas
// ==================

/** Calls [action] for each number below [count]: the lambda is only called, never kept. */
inline fun repeatInline(count: Int, action: (Int) -> Unit) {
	for (vI in 0 until count) action(vI)
}

fun repeatCalled(count: Int, action: (Int) -> Unit) {
	for (vI in 0 until count) action(vI)
}

/** A lambda that changes a variable and an object of the function around it. */
fun accumulate(count: Int): Long {
	val vAccumulator = Accumulator()
	var vOdd = 0
	repeatCalled(count) {
		vAccumulator.add(it)
		if (it % 2 == 1) vOdd++
	}
	repeatInline(count) { vAccumulator.add(it * 2) }
	return vAccumulator.total + vAccumulator.count + vOdd
}

// ==================
// MARK: Text
// ==================

/** A line of text built, measured and dropped. */
fun describe(point: Point, index: Int): Int {
	val vText = "point $index at (${point.x}, ${point.y})"
	val vParts = vText.split(" ")
	return vText.length + vParts.size + vParts[1].length
}

fun main() {
	var vSum = 0L

	for (vRound in 0 until 4000) vSum += walk(1000, vRound)
	println(vSum)

	for (vI in 1..3000000) {
		val (vQuotient, vRemainder) = divide(vI, 7)
		vSum += vQuotient - vRemainder
	}
	println(vSum)

	for (vI in 0 until 2000000) vSum += digits(vI)
	println(vSum)

	val vFixed = listOf(3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3)
	val vGrown = ArrayList<Int>()
	for (vI in 0 until 16) vGrown.add(vI * vI)
	val vOne = listOf(42)
	for (vRound in 0 until 400000) {
		vSum += total(vFixed) + total(vGrown) + total(vOne)
		vSum += weighted(vFixed) + weighted(vGrown)
	}
	println(vSum)

	for (vRound in 0 until 3000) vSum += accumulate(1000)
	println(vSum)

	for (vI in 0 until 300000) vSum += describe(Point(vI, -vI), vI)
	println(vSum)

	val vSquares = (1..200).map { it * it }
	for (vRound in 0 until 20000) {
		vSum += vSquares.filter { it % 3 == 0 }.sumOf { it.toLong() }
		vSum += vSquares.firstOrNull { it > vRound } ?: 0
		vSum += vSquares.zip(vSquares.reversed()).count { (vA, vB) -> vA < vB }
	}
	println(vSum)
}
