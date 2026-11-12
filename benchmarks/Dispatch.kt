// Benchmark: calls through interfaces and open classes, lambdas kept as values.

/** Something with a surface. */
interface Shape {
	fun area(): Double
	fun scale(inFactor: Double): Shape
}

class Circle(val radius: Double) : Shape {
	override fun area(): Double = 3.141592653589793 * radius * radius
	override fun scale(inFactor: Double): Shape = Circle(radius * inFactor)
}

class Rect(val width: Double, val height: Double) : Shape {
	override fun area(): Double = width * height
	override fun scale(inFactor: Double): Shape = Rect(width * inFactor, height * inFactor)
}

class Square(val side: Double) : Shape {
	override fun area(): Double = side * side
	override fun scale(inFactor: Double): Shape = Square(side * inFactor)
}

/** A class with one implementation of its method in the whole program. */
open class Counter {
	var total = 0L
	open fun add(inValue: Int) {
		total += inValue
	}
}

/** Applies [inOperation] [inTimes] times, starting from [inStart]. */
fun repeatApply(inStart: Int, inTimes: Int, inOperation: (Int) -> Int): Int {
	var vValue = inStart
	for (vI in 0 until inTimes) vValue = inOperation(vValue)
	return vValue
}

fun main() {
	val vShapes = ArrayList<Shape>()
	for (vI in 0 until 1000) {
		vShapes.add(when (vI % 3) {
			0 -> Circle(vI % 10 + 1.0)
			1 -> Rect(vI % 7 + 1.0, vI % 5 + 1.0)
			else -> Square(vI % 9 + 1.0)
		})
	}
	var vArea = 0.0
	for (vRound in 0 until 20000) {
		for (vShape in vShapes) vArea += vShape.area()
	}
	println(vArea)
	var vScaled = 0.0
	for (vRound in 0 until 1500) {
		for (vShape in vShapes) vScaled += vShape.scale(0.5).area()
	}
	println(vScaled)

	val vCounter = Counter()
	for (vI in 0 until 200000000) vCounter.add(vI and 7)
	println(vCounter.total)

	val vOperations = listOf<(Int) -> Int>({ it + 3 }, { it * 3 }, { it xor 0x55 }, { it - 7 })
	var vValue = 1
	for (vRound in 0 until 5000000) {
		for (vOperation in vOperations) vValue = vOperation(vValue)
	}
	println(vValue)
	println(repeatApply(1, 100000000) { it * 31 + 1 })
}
