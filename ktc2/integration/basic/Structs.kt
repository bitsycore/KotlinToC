package basic

import ktc2.Struct

@Struct
class Vec2(val x: Float, val y: Float) {
	fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
	fun scaled(factor: Float): Vec2 = Vec2(x * factor, y * factor)
	fun dot(other: Vec2): Float = x * other.x + y * other.y
}

@Struct
class Rect(val origin: Vec2, val size: Vec2) {
	fun area(): Float = size.x * size.y
	fun center(): Vec2 = origin.plus(size.scaled(0.5f))
	fun contains(p: Vec2): Boolean =
		p.x >= origin.x && p.y >= origin.y && p.x < origin.x + size.x && p.y < origin.y + size.y
}

@Struct
class Counter(val value: Int) {
	fun next(): Counter = Counter(value + 1)
}

fun makeRect(w: Float, h: Float): Rect = Rect(Vec2(1f, 2f), Vec2(w, h))

fun longest(a: Vec2, b: Vec2): Vec2 = if (a.dot(a) >= b.dot(b)) a else b

fun main() {
	val vA = Vec2(1.5f, 2f)
	val vB = vA.plus(Vec2(0.5f, -1f)).scaled(2f)
	println("${vB.x} ${vB.y}")
	println(vA.dot(vB))

	val vRect = makeRect(4f, 3f)
	println(vRect.area())
	val vCenter = vRect.center()
	println("${vCenter.x} ${vCenter.y}")
	println(vRect.contains(vCenter))
	println(vRect.contains(Vec2(0f, 0f)))
	println(vRect.size.x)

	val vLong = longest(vA, vB)
	println(vLong.x)

	var vCounter = Counter(0)
	for (vI in 0 until 5) vCounter = vCounter.next()
	println(vCounter.value)
}
