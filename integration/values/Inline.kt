package values

import ktc2.Size
import ktc2.Value

class Vec2(val x: Float, val y: Float) {
	fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
	fun length2(): Float = x * x + y * y
	override fun toString(): String = "($x, $y)"
}

class Label(val text: String, val size: Int)

class Counter(var count: Int) {
	fun bump() {
		count++
	}
}

/** Everything in a Body is stored in the Body itself: no pointer, no separate allocation. */
class Body(x: Float, y: Float, name: String) {
	var position: @Value Vec2 = Vec2(x, y)
	val velocity: @Value Vec2 = Vec2(1f, 0.5f)
	val label: @Value Label = Label(name, 12)
	val hits: @Value Counter = Counter(0)
	val history: @Size(4) IntArray = IntArray(4)
	val tags: @Size(2) Array<String> = arrayOf("new", name)
	var steps = 0

	fun step() {
		position = position.plus(velocity)
		history[steps % 4] = steps
		steps++
		hits.bump()
	}
}

class Registry {
	val kept = ArrayList<Vec2>()
	var last: Vec2? = null
}

fun describe(v: Vec2): String = "at $v"

fun farthest(a: Vec2, b: Vec2): Vec2 = if (a.length2() >= b.length2()) a else b

fun sum(values: IntArray): Int {
	var vTotal = 0
	for (vValue in values) vTotal += vValue
	return vTotal
}

fun main() {
	val vBody = Body(0f, 0f, "probe")
	println(vBody.position)
	for (vI in 0 until 6) vBody.step()
	println(vBody.position)
	println(vBody.position.x + vBody.velocity.y)
	println(describe(vBody.position))
	println(vBody.label.text + vBody.label.size)
	println(vBody.label.text.length)

	// A mutable object stored inline is changed in place.
	println(vBody.hits.count)
	vBody.hits.count += 10
	vBody.hits.bump()
	println(vBody.hits.count)

	// Inline arrays behave like arrays.
	println(vBody.history.size)
	println(sum(vBody.history))
	vBody.history[0] = 100
	println(vBody.history[0] + vBody.history[3])
	println(vBody.tags[0] + "/" + vBody.tags[1])
	vBody.tags[0] = "old"
	for (vTag in vBody.tags) print("$vTag ")
	println()

	// Assigning an inline field copies the value in.
	val vTarget = Vec2(10f, 20f)
	vBody.position = vTarget
	println(vBody.position)
	vBody.position = vBody.velocity
	println(vBody.position)

	// Where a reference has to be kept, an immutable inline value is copied out to the heap.
	val vRegistry = Registry()
	vRegistry.kept.add(vBody.position)
	vRegistry.last = vBody.velocity
	vBody.position = Vec2(-1f, -1f)
	println(vRegistry.kept[0])
	println(vRegistry.last)
	println(farthest(vBody.position, vBody.velocity))
	val vSecond = Body(5f, 5f, "other")
	vSecond.step()
	println(farthest(vBody.position, vSecond.position))
	println(vSecond.tags[1] + vSecond.steps)
}
