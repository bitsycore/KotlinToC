package sample

import ktc2.Size
import ktc2.Struct
import ktc2.Value

@Struct
class Vec2(val x: Float, val y: Float)

class Particle(var pos: @Value Vec2, var life: Int)

interface Shape {
	fun area(): Float
}

class Circle(val r: Float) : Shape {
	override fun area() = 3.14159f * r * r
}

open class Animal(val name: String) {
	open fun sound(): String = "..."
	fun describe() = "$name says ${sound()}"
}

class Dog(name: String) : Animal(name) {
	override fun sound() = "Woof"
}

class Emitter {
	val scratch: @Size(64) ByteArray = ByteArray(64)
	val pool: Array<@Value Particle> = Array(4) { Particle(Vec2(0f, 0f), it) }
}

fun fib(n: Int): Int {
	if (n < 2) return n
	var vA = 0
	var vB = 1
	var vI = 2
	while (vI <= n) {
		val vT = vA + vB
		vA = vB
		vB = vT
		vI = vI + 1
	}
	return vB
}

fun <T> firstOr(list: List<T>, default: T): T = if (list.isEmpty()) default else list[0]

fun describe(x: Any?): String = when (x) {
	null -> "null"
	is Int -> "int ${x + 1}"
	is String -> "str ${x.length}"
	is Shape -> "shape ${x.area()}"
	else -> "?"
}

fun main() {
	var vTotal = 0
	val vAdd = { v: Int -> vTotal += v }
	for (vI in 0 until 5) vAdd(vI)
	val vNames = mutableListOf("a", "bb")
	val vLens = vNames.map { it.length }
	val vDog: Animal = Dog("Rex")
	println(vDog.describe())
	println(fib(10))
	println(describe(vLens.sum()))
	println(describe(Circle(1f)))
	println(firstOr(vNames, "none"))
	println(vTotal)
	val vEmitter = Emitter()
	vEmitter.pool[1].life += 3
	println(vEmitter.pool[1].life)
	val vMaybe: String? = if (vTotal > 3) "x" else null
	println(vMaybe?.length ?: -1)
}
