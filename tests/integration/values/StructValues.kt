package values

import ktc2.Struct

interface Shape {
	fun area(): Float
}

@Struct
data class Point(val x: Float, val y: Float) : Comparable<Point> {
	operator fun plus(inOther: Point): Point = Point(x + inOther.x, y + inOther.y)

	operator fun times(inFactor: Float): Point = Point(x * inFactor, y * inFactor)

	fun length2(): Float = x * x + y * y

	override fun compareTo(other: Point): Int = length2().compareTo(other.length2())
}

@Struct
data class Box(val origin: Point, val size: Point) : Shape {
	override fun area(): Float = size.x * size.y

	fun center(): Point = origin + size * 0.5f
}

@Struct
data class Id(val value: Long)

class Sprite(val name: String, var position: Point, val bounds: Box) {
	fun move(inBy: Point) {
		position = position + inBy
	}
}

fun firstBeyond(inPoints: List<Point>, inLimit: Float): Point? {
	for (vPoint in inPoints) if (vPoint.length2() > inLimit) return vPoint
	return null
}

fun <T> describe(inValue: T): String = "<$inValue>"

fun <T : Comparable<T>> largest(inValues: List<T>): T {
	var vBest = inValues[0]
	for (vValue in inValues) if (vValue > vBest) vBest = vValue
	return vBest
}

fun totalArea(inShapes: List<Shape>): Float {
	var vTotal = 0f
	for (vShape in inShapes) vTotal += vShape.area()
	return vTotal
}

fun kind(inValue: Any?): String = when (inValue) {
	null -> "null"
	is Point -> "point ${inValue.x}"
	is Box -> "box ${inValue.area()}"
	is Id -> "id ${inValue.value}"
	else -> "other"
}

fun main() {
	val vA = Point(1f, 2f)
	val vB = vA.copy(y = 5f)

	// Value semantics: text, equality, hashing, destructuring.
	println(vA)
	println(vA == Point(1f, 2f))
	println(vA != vB)
	println(vA.hashCode() == Point(1f, 2f).hashCode())
	val (vX, vY) = vB
	println(vX + vY)
	println("$vA and ${vA + vB}")
	println(Box(vA, vB))
	println(Box(vA, vB) == Box(Point(1f, 2f), vB))

	// In collections: elements, keys, sorting, searching.
	val vPoints = listOf(vB, vA, vA + vB, Point(0f, 0.5f))
	println(vPoints.size)
	println(vPoints[2])
	println(vPoints.contains(Point(2f, 7f)))
	println(vPoints.indexOf(vA))
	println(vPoints.sorted())
	println(vPoints.maxOrNull())
	println(vPoints.map { it.x }.sum())
	println(vPoints.filter { it.y > 1f }.size)
	val vMutable = ArrayList<Point>()
	for (vIndex in 0 until 5) vMutable.add(Point(vIndex.toFloat(), 0f))
	vMutable[2] = vMutable[2] * 10f
	vMutable.removeAt(0)
	println(vMutable)
	val vNames = HashMap<Point, String>()
	vNames[vA] = "a"
	vNames[vB] = "b"
	vNames[Point(1f, 2f)] = "again"
	println("${vNames.size} ${vNames[vA]} ${vNames[Point(9f, 9f)]}")
	println(setOf(vA, vB, Point(1f, 2f)).size)
	val vArray = arrayOf(vA, vB)
	println(vArray[1].y)
	println(vArray.contentToString())

	// As an object: Any, interfaces, generics, nullable.
	val vAny: Any = vA
	println(vAny is Point)
	println(vAny == vA)
	println(vAny.toString())
	println(kind(vA))
	println(kind(Box(vA, vB)))
	println(kind(Id(77L)))
	println(kind(null))
	println(kind("text"))
	println(describe(vB))
	println(largest(vPoints))
	println(totalArea(listOf(Box(vA, vB), Box(vB, Point(2f, 2f)))))
	val vShape: Shape = Box(vA, Point(3f, 3f))
	println(vShape.area())
	println(firstBeyond(vPoints, 30f))
	println(firstBeyond(vPoints, 300f))
	val vFound = firstBeyond(vPoints, 20f)
	if (vFound != null) println(vFound.x)
	var vMaybe: Point? = null
	println(vMaybe?.x)
	vMaybe = vA
	println(vMaybe.x)
	println((vAny as Point).y)
	println((vAny as? Box) == null)

	// Inside objects.
	val vSprite = Sprite("ship", vA, Box(Point(0f, 0f), Point(4f, 2f)))
	vSprite.move(Point(2f, 2f))
	vSprite.move(Point(-1f, 0f))
	println("${vSprite.name} ${vSprite.position} ${vSprite.bounds.center()}")
	println(Pair(vA, Id(5L)))
	println("done")
}
