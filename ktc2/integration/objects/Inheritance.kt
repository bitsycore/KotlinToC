package objects

interface Shape {
	val name: String
	fun area(): Double
	fun describe(): String = "$name with area ${area()}"
}

interface Scalable {
	fun scaled(factor: Double): Shape
}

abstract class Base(val id: Int) : Shape {
	var uses = 0
	abstract fun corners(): Int
	open fun label(): String = "#$id"
	fun touch(): Base {
		uses++
		return this
	}
	override fun toString(): String = "${label()} $name"
}

open class Rect(id: Int, val w: Double, val h: Double) : Base(id), Scalable {
	override val name: String get() = "rect"
	override fun area(): Double = w * h
	override fun corners(): Int = 4
	override fun scaled(factor: Double): Shape = Rect(id + 100, w * factor, h * factor)
}

class Square(id: Int, side: Double) : Rect(id, side, side) {
	override val name = "square"
	override fun label(): String = super.label() + "s"
	override fun describe(): String = "a " + super.describe()
}

class Circle(id: Int, val r: Double) : Base(id) {
	override val name = "circle"
	override fun area(): Double = 3.0 * r * r
	override fun corners(): Int = 0
	override fun equals(other: Any?): Boolean = other is Circle && other.r == r
	override fun hashCode(): Int = r.toInt()
}

class Blob : Shape {
	override val name = "blob"
	override fun area(): Double = 0.5
}

object Registry {
	var count = 0
	val first = Blob()
	fun register(s: Shape): Int {
		count++
		println("registered ${s.name}")
		return count
	}
}

class Factory private constructor(val prefix: String) {
	companion object {
		const val LIMIT = 3
		var made = 0
		fun create(prefix: String): Factory {
			made++
			return Factory(prefix)
		}
	}

	fun tag(n: Int): String = prefix + n
}

fun totalArea(a: Shape, b: Shape, c: Shape): Double = a.area() + b.area() + c.area()

fun kind(x: Any?): String = when (x) {
	null -> "null"
	is Square -> "square ${x.w}"
	is Rect -> "rect ${x.w}x${x.h}"
	is Base -> "base ${x.corners()}"
	is Shape -> "shape ${x.name}"
	is String -> "string ${x.length}"
	else -> "other"
}

fun main() {
	val vRect = Rect(1, 2.0, 3.0)
	val vSquare = Square(2, 4.0)
	val vCircle = Circle(3, 1.0)
	val vBlob = Blob()

	// Virtual calls through class and interface types
	val vBase: Base = vSquare
	val vShape: Shape = vCircle
	println(vBase.area())
	println(vBase.label())
	println(vBase.corners())
	println(vShape.area())
	println(vShape.describe())
	println(vSquare.describe())
	println(vBlob.describe())
	println(totalArea(vRect, vSquare, vBlob))
	println(vRect)
	println(vSquare)
	println("$vCircle / $vBase")
	println(vSquare.touch().touch().uses)
	println(vRect.name + vSquare.name + vShape.name)

	// Interface returning an interface
	val vScaled = vSquare.scaled(0.5)
	println(vScaled.describe())
	val vScalable: Scalable = vRect
	println(vScalable.scaled(2.0).area())

	// is / as / smart casts
	println(kind(vRect))
	println(kind(vSquare))
	println(kind(vCircle))
	println(kind(vBlob))
	println(kind("text"))
	println(kind(null))
	println(kind(Registry))
	val vAny: Any = vSquare
	val vAgain = vAny as Rect
	println(vAgain.h)
	val vNot = vAny as? Circle
	println(vNot == null)
	println((vAny as? Scalable) != null)
	println(vShape is Scalable)
	println(vShape !is Rect)
	if (vAny is Shape && vAny.area() > 10) println("big ${vAny.name}")

	// equals / hashCode overrides and defaults
	println(vCircle == Circle(9, 1.0))
	println(vCircle == Circle(9, 2.0))
	println(vShape == vCircle)
	val vOther: Any = Circle(4, 1.0)
	println(vOther == vShape)
	println(vRect == Rect(1, 2.0, 3.0))
	println(vRect == vRect)
	println(vCircle.hashCode())
	println(vRect.hashCode() == vRect.hashCode())
	println(vBlob != vBlob)

	// Objects and companions
	println(Registry.register(vRect))
	println(Registry.register(Registry.first))
	println(Registry.count)
	val vFactory = Factory.create("id-")
	println(vFactory.tag(Factory.LIMIT))
	println(Factory.create("x").tag(Factory.made))
}
