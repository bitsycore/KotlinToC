package shapes

import kotlin.math.PI
import kotlin.math.sqrt

// ==================
// MARK: Shapes
// ==================

/** Something flat with a surface and an outline. */
interface Shape {
	val name: String

	fun area(): Double

	fun perimeter(): Double
}

class Circle(val radius: Double) : Shape {
	override val name: String get() = "circle"

	override fun area(): Double = PI * radius * radius

	override fun perimeter(): Double = 2.0 * PI * radius
}

/** A shape with straight sides, of which it knows the number. */
abstract class Polygon(val sides: Int) : Shape {
	override fun toString(): String = "$name with $sides sides"
}

open class Rectangle(val width: Double, val height: Double) : Polygon(4) {
	override val name: String get() = "rectangle"

	override fun area(): Double = width * height

	override fun perimeter(): Double = 2.0 * (width + height)
}

class Square(side: Double) : Rectangle(side, side) {
	override val name: String get() = "square"
}

class Triangle(val a: Double, val b: Double, val c: Double) : Polygon(3) {
	init {
		require(a + b > c && a + c > b && b + c > a) { "no triangle has sides $a, $b and $c" }
	}

	override val name: String get() = "triangle"

	override fun area(): Double {
		val vHalf = perimeter() / 2.0
		return sqrt(vHalf * (vHalf - a) * (vHalf - b) * (vHalf - c))
	}

	override fun perimeter(): Double = a + b + c
}
