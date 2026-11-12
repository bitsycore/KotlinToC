// A function that is called with objects of several classes for one parameter is copied for
// the calls that pass a known class. Every call has to do what the function does, whichever
// copy it reaches.

package copies

interface Shape {
	val name: String

	fun area(): Int

	fun grown(by: Int): Shape
}

class Square(private val mSide: Int) : Shape {
	override val name: String get() = "square"

	override fun area(): Int = mSide * mSide

	override fun grown(by: Int): Shape = Square(mSide + by)
}

class Strip(private val mLength: Int) : Shape {
	override val name: String get() = "strip"

	override fun area(): Int = mLength

	override fun grown(by: Int): Shape = if (by > 2) Square(by) else Strip(mLength + by)
}

object Nothingness : Shape {
	override val name: String get() = "nothing"

	override fun area(): Int = 0

	override fun grown(by: Int): Shape = this
}

/** A list of its own kind. */
class Evens(override val size: Int) : AbstractList<Int>() {
	override fun get(index: Int): Int = index * 2
}

// ==================
// MARK: One parameter
// ==================

fun total(values: List<Int>): Int {
	var vTotal = 0
	for (vValue in values) vTotal += vValue
	return vTotal
}

fun describe(shape: Shape): String = "${shape.name} ${shape.area()}"

/** Passes its parameter on: the copy calls a copy. */
fun describeTwice(shape: Shape): String = describe(shape) + ", " + describe(shape.grown(1))

/** Calls itself with what it was given, and with something else. */
fun countDown(shape: Shape, steps: Int): Int {
	if (steps == 0) return shape.area()
	return shape.area() + countDown(if (steps % 2 == 0) shape else shape.grown(steps), steps - 1)
}

/** The parameter is given another object inside. */
fun largest(shape: Shape, rounds: Int): Int {
	var vShape = shape
	var vBest = 0
	for (vI in 0 until rounds) {
		if (vShape.area() > vBest) vBest = vShape.area()
		vShape = vShape.grown(vI)
	}
	return vBest
}

/** Null is not an object of the class. */
fun areaOrNone(shape: Shape?): Int = shape?.area() ?: -1

/** Asks what it was given. */
fun kind(value: Any): String = when (value) {
	is Square -> "a square of ${value.area()}"
	is Shape -> "a shape"
	is List<*> -> "a list of ${value.size}"
	else -> "something"
}

// ==================
// MARK: Two parameters
// ==================

fun both(first: Shape, second: Shape): Int = first.area() * 10 + second.area()

fun zipTotal(left: List<Int>, right: List<Int>): Int {
	var vTotal = 0
	val vRight = right.iterator()
	for (vValue in left) if (vRight.hasNext()) vTotal += vValue * vRight.next()
	return vTotal
}

// ==================
// MARK: Not by a plain call
// ==================

fun apply(shapes: List<Shape>, action: (Shape) -> String): String = shapes.joinToString("; ") { action(it) }

class Holder(val shape: Shape) {
	fun text(): String = describe(shape)
}

fun main() {
	val vGrown = ArrayList<Int>()
	for (vI in 1..6) vGrown.add(vI)
	println("${total(vGrown)} ${total(listOf(7, 8))} ${total(emptyList())} ${total(Evens(5))} ${total(vGrown.subList(2, 4))} ${total(vGrown)}")

	val vSquare = Square(3)
	val vStrip = Strip(4)
	println(describe(vSquare) + " | " + describe(vStrip) + " | " + describe(Nothingness) + " | " + describe(vStrip.grown(5)))
	println(describeTwice(vSquare) + " | " + describeTwice(vStrip) + " | " + describeTwice(Nothingness))
	println("${countDown(vSquare, 5)} ${countDown(vStrip, 5)} ${countDown(Nothingness, 3)}")
	println("${largest(vSquare, 4)} ${largest(vStrip, 6)} ${largest(Nothingness, 2)}")
	println("${areaOrNone(vSquare)} ${areaOrNone(null)} ${areaOrNone(vStrip)} ${areaOrNone(if (vGrown.size > 100) vSquare else null)}")
	println(kind(vSquare) + ", " + kind(vStrip) + ", " + kind(vGrown) + ", " + kind("text") + ", " + kind(Evens(2)))

	println("${both(vSquare, vStrip)} ${both(vStrip, vSquare)} ${both(vSquare, vSquare)} ${both(Nothingness, vStrip)} ${both(vStrip.grown(1), vSquare.grown(1))}")
	println("${zipTotal(vGrown, Evens(4))} ${zipTotal(Evens(4), vGrown)} ${zipTotal(vGrown, vGrown)} ${zipTotal(listOf(1, 2), vGrown)}")

	val vShapes = listOf(vSquare, vStrip, Nothingness)
	println(apply(vShapes, ::describe))
	println(apply(vShapes) { describeTwice(it) })
	println(Holder(vSquare).text() + " " + Holder(vStrip).text())

	var vSum = 0L
	for (vRound in 0 until 2000) {
		vSum += total(vGrown) + total(Evens(vRound % 5)) + both(vSquare, vStrip) + largest(if (vRound % 2 == 0) vSquare else vStrip, 3)
	}
	println(vSum)
}
