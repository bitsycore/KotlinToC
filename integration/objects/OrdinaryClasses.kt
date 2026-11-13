// Ordinary classes: data classes, enums, sealed classes, objects, delegation, operators.

data class Point(val x: Int, val y: Int) {
	operator fun plus(inOther: Point) = Point(x + inOther.x, y + inOther.y)
	operator fun times(inBy: Int) = Point(x * inBy, y * inBy)
	operator fun unaryMinus() = Point(-x, -y)
	operator fun compareTo(inOther: Point): Int = (x * x + y * y) - (inOther.x * inOther.x + inOther.y * inOther.y)
}

data class Person(val name: String, var age: Int, val tags: List<String> = emptyList(), val score: Double? = null)

enum class Level(val weight: Int) {
	LOW(1), MIDDLE(5), HIGH(10) {
		override fun label(): String = "top"
	};

	open fun label(): String = name.lowercase()
	fun heavier(): Level = entries[(ordinal + 1) % entries.size]
}

sealed class Expr
class Num(val value: Double) : Expr()
class Add(val left: Expr, val right: Expr) : Expr()
class Mul(val left: Expr, val right: Expr) : Expr()
object Zero : Expr()

fun eval(inExpr: Expr): Double = when (inExpr) {
	is Num -> inExpr.value
	is Add -> eval(inExpr.left) + eval(inExpr.right)
	is Mul -> eval(inExpr.left) * eval(inExpr.right)
	Zero -> 0.0
}

interface Greeter {
	val greeting: String get() = "hello"
	fun greet(inName: String): String = "$greeting, $inName"
}
class Polite : Greeter { override val greeting: String get() = "good day" }
class Plain : Greeter
class Loud(private val mInner: Greeter) : Greeter by mInner {
	override fun greet(inName: String): String = mInner.greet(inName).uppercase() + "!"
}

class Matrix(val rows: Int, val columns: Int) {
	private val mCells = DoubleArray(rows * columns)
	operator fun get(inRow: Int, inColumn: Int): Double = mCells[inRow * columns + inColumn]
	operator fun set(inRow: Int, inColumn: Int, inValue: Double) { mCells[inRow * columns + inColumn] = inValue }
	operator fun contains(inValue: Double): Boolean = mCells.any { it == inValue }
	operator fun invoke(inRow: Int): List<Double> = List(columns) { this[inRow, it] }
}

class Registry {
	companion object {
		const val kLimit = 3
		private var sCount = 0
		fun next(): Int = ++sCount
	}
	val id = next()
	inner class Entry(val key: String) { fun describe(): String = "$key@$id" }
}

class Lazy { val heavy: String by lazy { println("computed"); "value" } ; lateinit var late: String }

lateinit var gLate: String

class Holder {
	lateinit var item: Point
	fun has(): Boolean = ::item.isInitialized
	fun inside(): String = try { item.toString() } catch (inError: UninitializedPropertyAccessException) { "inside: ${inError.message}" }
}

abstract class Animal(val name: String) {
	abstract fun sound(): String
	open fun describe(): String = "$name says ${sound()}"
	override fun toString(): String = "Animal($name)"
}
class Dog : Animal("dog") { override fun sound() = "woof" }
class Puppy : Animal("puppy") { override fun sound() = "yip"; override fun describe() = super.describe() + " softly" }

fun main() {
	val vPoint = Point(1, 2)
	val (vX, vY) = vPoint
	println("$vPoint $vX $vY ${vPoint + Point(3, 4)} ${vPoint * 3} ${-vPoint} ${vPoint == Point(1, 2)} ${vPoint === vPoint} ${vPoint.hashCode() == Point(1, 2).hashCode()} ${vPoint < Point(2, 2)} ${vPoint.copy(y = 9)}")
	val vPoints = hashSetOf(Point(1, 1), Point(1, 1), Point(2, 1))
	println("${vPoints.size} ${Point(2, 1) in vPoints} ${hashMapOf(Point(0, 0) to "origin")[Point(0, 0)]}")
	val vPerson = Person("Ann", 30, listOf("a", "b"))
	val vOlder = vPerson.copy(age = 31, score = 9.5)
	vPerson.age++
	println("$vPerson $vOlder ${vPerson == vOlder} ${vPerson.copy(score = 9.5) == vOlder} ${vOlder.component3()} ${Person("Bob", 1).tags.isEmpty()}")

	println("${Level.values().toList()} ${Level.valueOf("HIGH")} ${Level.HIGH.label()} ${Level.LOW.label()} ${Level.HIGH.heavier()} ${Level.MIDDLE.weight} ${Level.LOW < Level.HIGH} ${Level.entries.map { it.ordinal }} ${Level.MIDDLE.compareTo(Level.HIGH)}")
	val vByLevel = Level.entries.associateWith { it.weight * 2 }
	println("$vByLevel ${Level.entries.maxByOrNull { it.weight }} ${enumValueOf<Level>("LOW")} ${enumValues<Level>().size}")
	for (vLevel in Level.entries) {
		val vText = when (vLevel) { Level.LOW -> "l"; Level.MIDDLE -> "m"; Level.HIGH -> "h" }
		print(vText)
	}
	println()
	try { Level.valueOf("NONE") } catch (inError: IllegalArgumentException) { println("no such level") }

	val vExpr = Add(Num(2.0), Mul(Num(3.0), Add(Num(1.0), Zero)))
	println("${eval(vExpr)} ${eval(Zero)} ${vExpr.left is Num} ${Zero === Zero}")

	val vGreeters = listOf(Plain(), Polite(), Loud(Polite()), Loud(Plain()), object : Greeter { override val greeting = "yo" })
	println(vGreeters.map { it.greet("Kim") })

	val vMatrix = Matrix(2, 3)
	vMatrix[1, 2] = 4.5
	vMatrix[0, 0] += 1.0
	println("${vMatrix[1, 2]} ${vMatrix[0, 0]} ${4.5 in vMatrix} ${9.0 in vMatrix} ${vMatrix(1)}")

	val vFirst = Registry()
	val vSecond = Registry()
	println("${vFirst.id} ${vSecond.id} ${Registry.kLimit} ${vSecond.Entry("k").describe()} ${Registry.next()}")
	val vLazy = Lazy()
	println("before")
	println("${vLazy.heavy} ${vLazy.heavy}")
	try { println(vLazy.late) } catch (inError: UninitializedPropertyAccessException) { println("not set") }
	vLazy.late = "set"
	println(vLazy.late)

	try { println(gLate) } catch (inError: UninitializedPropertyAccessException) { println(inError.message) }
	gLate = "g"
	println(gLate)
	val vHolder = Holder()
	println("${vHolder.has()} ${vHolder.inside()}")
	try { println(vHolder.item.x) } catch (inError: UninitializedPropertyAccessException) { println(inError.message) }
	vHolder.item = Point(1, 1)
	println("${vHolder.has()} ${vHolder.inside()} ${vHolder.item.x}")
	lateinit var vLocal: String
	try { println(vLocal) } catch (inError: UninitializedPropertyAccessException) { println(inError.message) }
	vLocal = "l"
	println(vLocal)

	val vAnimals: List<Animal> = listOf(Dog(), Puppy())
	println("${vAnimals.map { it.describe() }} $vAnimals")
	val vCounter = object { var count = 0; fun bump() = ++count }
	vCounter.bump(); vCounter.bump()
	println(vCounter.count)
	var vCaptured = 0
	val vAdders = List(3) { vIndex -> { inBy: Int -> vCaptured += inBy * vIndex; vCaptured } }
	println(vAdders.map { it(2) })
	fun local(inDepth: Int): Int = if (inDepth == 0) vCaptured else local(inDepth - 1) + 1
	println(local(3))
}
