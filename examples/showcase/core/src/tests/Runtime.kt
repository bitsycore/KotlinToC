package showcase.core.tests

import ktc2.Size
import ktc2.Struct
import ktc2.ValueArray
import ktc2.WeakRef
import ktc2.arena
import ktc2.concurrent.sleep
import ktc2.concurrent.synchronized
import ktc2.concurrent.thread
import shapes.Circle
import shapes.Polygon
import shapes.Rectangle
import shapes.Shape
import shapes.Square
import shapes.Transform
import shapes.Triangle
import shapes.areasByName
import shapes.describe
import shapes.rounded
import shapes.transformed
import showcase.core.Group
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

// ==================
// MARK: Errors
// ==================

private class InsufficientFunds(val missing: Int) : Exception("missing $missing")

private class Account(var balance: Int) {
	val log = ArrayList<String>()

	fun withdraw(inAmount: Int): Int {
		require(inAmount > 0) { "amount must be positive" }
		try {
			if (inAmount > balance) throw InsufficientFunds(inAmount - balance)
			balance -= inAmount
			log.add("took $inAmount")
			return balance
		} finally {
			log.add("checked $inAmount")
		}
	}
}

fun errorsGroup(): Group = Group("Errors") { r ->
	val vAccount = Account(100)
	r.expect("normal path", 70, vAccount.withdraw(30))
	val vMissing = try {
		vAccount.withdraw(500)
		-1
	} catch (vError: InsufficientFunds) {
		vError.missing
	}
	r.expect("custom exception", 430, vMissing)
	r.expect("finally always runs", "[took 30, checked 30, checked 500]", vAccount.log.toString())
	r.fails("require", "amount must be positive") { vAccount.withdraw(0) }
	r.fails("check", "not ready") { check(vAccount.balance > 1000) { "not ready" } }
	r.fails("index out of bounds", "Index 5 out of bounds for length 3") { listOf(1, 2, 3)[5] }
	r.fails("division by zero", "/ by zero") { println(10 / (vAccount.balance - 70)) }
	r.fails("number format", "For input string: \"12x\"") { "12x".toInt() }
	r.fails("null assertion", null) { val vNothing: String? = null; vNothing!!.length }

	var vKept = 5
	try {
		vKept = vAccount.withdraw(9999)
	} catch (vError: Exception) {
	}
	r.expect("a failed assignment changes nothing", 5, vKept)

	val vResults = listOf("12", "x", "7").map { vText -> runCatching { vText.toInt() } }
	r.expect("runCatching", "[12, -1, 7] 1", "${vResults.map { it.getOrDefault(-1) }} ${vResults.count { it.isFailure }}")
	val vNested = try {
		try {
			error("inner")
		} catch (vError: IllegalStateException) {
			throw IllegalArgumentException("outer after " + vError.message, vError)
		}
	} catch (vError: IllegalArgumentException) {
		vError.message + " / " + vError.cause?.message
	}
	r.expect("rethrow with a cause", "outer after inner / inner", vNested)
	r.expect("catch by supertype", "RuntimeException", try {
		throw IndexOutOfBoundsException("x")
	} catch (vError: IllegalStateException) {
		"IllegalStateException"
	} catch (vError: RuntimeException) {
		"RuntimeException"
	})
}

// ==================
// MARK: Values
// ==================

@Struct
private data class Vec(val x: Float, val y: Float) {
	operator fun plus(inOther: Vec): Vec = Vec(x + inOther.x, y + inOther.y)

	operator fun times(inFactor: Float): Vec = Vec(x * inFactor, y * inFactor)

	fun dot(inOther: Vec): Float = x * inOther.x + y * inOther.y
}

private class Particle(var position: Vec, val velocity: Vec) {
	fun advance(inTime: Float) {
		position = position + velocity * inTime
	}
}

private class Node(val name: String) {
	val children = ArrayList<Node>()
	var parent: WeakRef<Node>? = null

	fun add(inChild: Node): Node {
		inChild.parent = WeakRef(this)
		children.add(inChild)
		return this
	}

	fun path(): String = (parent?.get()?.path()?.plus("/") ?: "") + name
}

private fun centroid(inPoints: List<Vec>): Vec = arena(4096) {
	// What is made here and does not leave is given back in one go when the block ends.
	val vScaled = inPoints.map { it * (1.0f / inPoints.size) }
	vScaled.fold(Vec(0.0f, 0.0f)) { vSum, vPoint -> vSum + vPoint }
}

fun valuesGroup(): Group = Group("Values") { r ->
	val vA = Vec(1.0f, 2.0f)
	val vB = Vec(3.0f, -1.0f)
	r.expect("structs by value", "Vec(x=4.0, y=1.0) 1.0", "${vA + vB} ${vA.dot(vB)}")
	r.expect("struct equality", true, vA + vB == Vec(4.0f, 1.0f) && vA != vB)
	val vParticles = List(3) { Particle(Vec(it.toFloat(), 0.0f), Vec(1.0f, 2.0f)) }
	repeat(4) { vParticles.forEach { it.advance(0.5f) } }
	r.expect("structs in objects", "[Vec(x=2.0, y=4.0), Vec(x=3.0, y=4.0), Vec(x=4.0, y=4.0)]", vParticles.map { it.position }.toString())
	r.expect("structs in collections", Vec(2.0f, 2.0f), centroid(listOf(Vec(0.0f, 0.0f), Vec(4.0f, 0.0f), Vec(2.0f, 6.0f))))

	val vInline = ValueArray(4) { Vec(it.toFloat(), it * 2.0f) }
	vInline[2] = vInline[1] + vInline[3]
	r.expect("value arrays", "Vec(x=4.0, y=8.0) 4", "${vInline[2]} ${vInline.size}")
	@Size(8) val vBuffer = IntArray(8)
	for (vIndex in vBuffer.indices) vBuffer[vIndex] = vIndex * vIndex
	r.expect("arrays in the frame", 140, vBuffer.sum())
	val vSizes = IntArray(5) { it + 1 }.map { vCount -> IntArray(vCount) { it }.sum() }
	r.expect("arrays of run-time size", listOf(0, 1, 3, 6, 10), vSizes)

	val vRoot = Node("root").add(Node("usr").add(Node("bin"))).add(Node("etc"))
	r.expect("weak references", "root/usr/bin root/etc", vRoot.children[0].children[0].path() + " " + vRoot.children[1].path())
	var vOrphan: Node? = Node("temp").add(Node("leaf"))
	val vLeaf = vOrphan!!.children[0]
	val vBefore = vLeaf.path()
	vOrphan = null
	r.expect("a weak reference does not keep its target", "temp/leaf", vBefore)
	r.check("the leaf outlives its parent", vLeaf.name == "leaf")
}

// ==================
// MARK: Concurrency
// ==================

private class Tally {
	private var mCount = 0

	fun add(inAmount: Int) {
		synchronized(this) { mCount += inAmount }
	}

	fun value(): Int = synchronized(this) { mCount }
}

fun concurrencyGroup(): Group = Group("Concurrency") { r ->
	val vTally = Tally()
	val vThreads = List(4) { vWorker -> thread { for (vStep in 0 until 500) vTally.add(vWorker + 1) } }
	vThreads.forEach { it.join() }
	r.expect("threads and a monitor", 5000, vTally.value())

	val vResults = IntArray(6)
	val vWorkers = List(6) { vIndex -> thread { vResults[vIndex] = (1..(vIndex + 1) * 100).sum() } }
	vWorkers.forEach { it.join() }
	r.expect("results of threads", listOf(5050, 20100, 45150, 80200, 125250, 180300), vResults.toList())

	val vShared = ArrayList<String>()
	val vWriters = List(3) { vIndex -> thread { repeat(20) { synchronized(vShared) { vShared.add("w$vIndex") } } } }
	vWriters.forEach { it.join() }
	r.expect("a shared list", "60 20", "${vShared.size} ${vShared.count { it == "w1" }}")

	val vLate = thread(start = false) { vTally.add(1000) }
	val vBeforeStart = vTally.value()
	vLate.start()
	vLate.join()
	r.expect("a thread started later", "5000 6000", "$vBeforeStart ${vTally.value()}")

	val vTook = measureTime { sleep(15) }
	r.check("sleeping takes its time", vTook >= 10.milliseconds && vTook < 5.seconds)
	r.expect("durations", "1.5s 90", "${1500.milliseconds} ${1.5.seconds.inWholeMilliseconds / 1500 * 90}")
}

// ==================
// MARK: Shapes (the other module)
// ==================

fun shapesGroup(): Group = Group("Shapes module") { r ->
	val vShapes: List<Shape> = listOf(Circle(1.0), Rectangle(2.0, 3.0), Square(2.0), Triangle(3.0, 4.0, 5.0), Circle(2.0))
	r.expect("describe", "circle: area 3.14, perimeter 6.28", describe(vShapes[0]))
	r.expect("inheritance across modules", "[rectangle with 4 sides, square with 4 sides, triangle with 3 sides]", vShapes.filterIsInstance<Polygon>().toString())
	r.expect("areas by name", "{circle=15.71, rectangle=6.0, square=4.0, triangle=6.0}", areasByName(vShapes).toString())
	val vScaled = vShapes.map { it.transformed(Transform.Scale(2.0)) }
	r.expect("sealed transforms", listOf(12.57, 24.0, 16.0, 24.0, 50.27), vScaled.map { rounded(it.area()) })
	r.expect("kinds are kept", vShapes.map { it.name }, vScaled.map { it.name })
	r.expect("identity and grow", "true 9.0", "${vShapes[2].transformed(Transform.Identity) === vShapes[2]} ${vShapes[2].transformed(Transform.Grow(1.0)).area()}")
	r.expect("data classes of the module", "Scale(factor=2.0) true", "${Transform.Scale(2.0)} ${Transform.Grow(1.0) == Transform.Grow(1.0)}")
	r.fails("a constructor that refuses", "no triangle has sides 1.0, 1.0 and 5.0") { Triangle(1.0, 1.0, 5.0) }
	r.expect("largest by area", "circle", vShapes.maxByOrNull { it.area() }?.name)
}
