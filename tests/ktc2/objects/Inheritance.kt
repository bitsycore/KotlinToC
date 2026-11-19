package ktc2test.objects.inheritance

import kotlin.test.Test

// ==================
// MARK: Inheritance
// ==================

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

@Test
fun inheritance() {
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

// ==================
// MARK: InheritanceBasics
// ==================

// Integration test for class inheritance: open/abstract/sealed classes,
// data + method inheritance, virtual dispatch through parent-typed values,
// multi-level chains, sealed-class when, and exceptions via class extension.

// ==================
// MARK: Open class hierarchy
// ==================

open class Animal(val name: String, val legs: Int = 4) {
    open fun sound(): Int = 0
    fun describe(): Int = name.length * 10 + legs   // final - inherited as-is
}

open class Dog(name: String, val breed: String) : Animal(name) {
    override fun sound(): Int = 1
}

class Puppy(name: String) : Dog(name, "unknown") {
    override fun sound(): Int = 2
}

class Bird(name: String) : Animal(name, 2)
class Snake(name: String) : Animal(legs = 0, name = name)   // named super-args
class Spider(name: String) : Animal(name, legs = 8)         // positional + named mix

// ==================
// MARK: Abstract class with abstract + concrete members
// ==================

abstract class BasicShape(val sides: Int) {
    abstract fun area(): Float
    fun cornerCount(): Int = sides            // concrete helper, inherited
    open fun isRound(): Boolean = false
}

class BasicRect(val w: Float, val h: Float) : BasicShape(4) {
    override fun area(): Float = w * h
}

class BasicCircle(val r: Float) : BasicShape(0) {
    override fun area(): Float = 3.14159f * r * r
    override fun isRound(): Boolean = true
}

// ==================
// MARK: Sealed class hierarchy + when exhaustiveness
// ==================

sealed class Node(val id: Int)
class Leaf(id: Int, val value: Int) : Node(id)
class Branch(id: Int, val childCount: Int) : Node(id)

fun nodeWeight(n: Node): Int = when (n) {
    is Leaf   -> n.value
    is Branch -> n.childCount * 100
}

// ==================
// MARK: Inherited var props (writable through parent-typed values)
// ==================

open class Gauge(var level: Int, val max: Int = 100) {
    open fun fill() { level = max }
}

class HalfGauge : Gauge(0) {
    override fun fill() { level = max / 2 }
}

// ==================
// MARK: super calls
// ==================

open class Counter(val base: Int) {
    open fun next(): Int = base + 1
}

open class Doubling(base: Int) : Counter(base) {
    override fun next(): Int = super.next() * 2
}

class DoublingPlus(base: Int) : Doubling(base) {
    override fun next(): Int = super.next() + 5      // chains: ((base+1)*2)+5
    fun baseViaSuper(): Int = super.base             // super.prop → this.prop
}

// ==================
// MARK: Generic parent classes
// ==================

abstract class Box<T>(val value: T) {
    abstract fun describe(): Int
    fun unwrap(): T = value                     // inherited generic method
}

class IntBox(v: Int) : Box<Int>(v) {
    override fun describe(): Int = value * 2
}

class StrBox(v: String) : Box<String>(v) {
    override fun describe(): Int = value.length
}

open class Pair2<T>(val first: T, var second: T)
class IntPair(a: Int, b: Int) : Pair2<Int>(a, b)

// ==================
// MARK: Exceptions through class inheritance
// ==================

open class AppError(message: String, val code: Int) : Exception(message)
class ConfigError(message: String) : AppError(message, 78)

@Test
fun inheritanceBasics() {
    // Data inheritance: fields flow down the chain (with defaults).
    val d = Dog("rex", "lab")
    if (d.name != "rex") error("FAIL dog.name")
    if (d.legs != 4) error("FAIL dog.legs default")
    if (d.breed != "lab") error("FAIL dog.breed")
    val b = Bird("tweety")
    if (b.legs != 2) error("FAIL bird.legs super-arg")
    val sn = Snake("sss")
    if (sn.legs != 0 || sn.name != "sss") error("FAIL named super-args")
    val sp = Spider("web")
    if (sp.legs != 8 || sp.name != "web") error("FAIL mixed super-args")
    println("data inheritance: OK")

    // Method inheritance + override, on concrete values.
    if (d.sound() != 1) error("FAIL dog.sound")
    if (d.describe() != 34) error("FAIL dog.describe inherited")
    val p = Puppy("io")
    if (p.sound() != 2) error("FAIL puppy.sound")
    if (p.breed != "unknown") error("FAIL puppy.breed via Dog default chain")
    if (p.describe() != 24) error("FAIL puppy.describe two levels up")
    println("method inheritance: OK")

    // Virtual dispatch through a parent-typed value (fat value, vtable).
    val animals0: Animal = Dog("a", "x")
    val animals1: Animal = Puppy("bb")
    val animals2: Animal = Animal("ccc")     // direct open-class instance
    val animals3: Animal = Bird("dddd")
    if (animals0.sound() != 1) error("FAIL virtual dog")
    if (animals1.sound() != 2) error("FAIL virtual puppy")
    if (animals2.sound() != 0) error("FAIL virtual base")
    if (animals3.sound() != 0) error("FAIL virtual bird inherits base")
    if (animals1.describe() != 24) error("FAIL virtual describe")
    if (animals3.name != "dddd") error("FAIL property via parent type")
    println("virtual dispatch: OK")

    // is / !is on parent-typed values, down the chain.
    if (animals1 !is Puppy) error("FAIL is Puppy")
    if (animals1 !is Dog) error("FAIL puppy is Dog")
    if (animals0 is Puppy) error("FAIL dog is not Puppy")
    println("is-checks: OK")

    // Abstract class: dispatch + inherited concrete + open default.
    val s0: BasicShape = BasicRect(2.0f, 3.0f)
    val s1: BasicShape = BasicCircle(1.0f)
    if (s0.area() != 6.0f) error("FAIL rect area")
    if (s1.area() < 3.0f) error("FAIL circle area")
    if (s0.cornerCount() != 4) error("FAIL inherited cornerCount")
    if (s0.isRound()) error("FAIL rect isRound default")
    if (!s1.isRound()) error("FAIL circle isRound override")
    println("abstract class: OK")

    // Sealed class: exhaustive when over subclasses.
    if (nodeWeight(Leaf(1, 7)) != 7) error("FAIL leaf weight")
    if (nodeWeight(Branch(2, 3)) != 300) error("FAIL branch weight")
    println("sealed when: OK")

    // Inherited var props: write through concrete AND parent-typed values.
    val hg = HalfGauge()
    hg.level = 7
    if (hg.level != 7) error("FAIL concrete var write")
    val g: Gauge = HalfGauge()
    g.level = 33
    if (g.level != 33) error("FAIL var write via parent ${g.level}")
    g.fill()
    if (g.level != 50) error("FAIL fill via parent ${g.level}")
    println("var props: OK")

    // super.method() through multi-level chains + super.prop.
    if (Doubling(3).next() != 8) error("FAIL Doubling.next")
    val dp = DoublingPlus(3)
    if (dp.next() != 13) error("FAIL DoublingPlus.next ${dp.next()}")
    if (dp.baseViaSuper() != 3) error("FAIL super.prop")
    val viaParent: Counter = DoublingPlus(10)
    if (viaParent.next() != 27) error("FAIL virtual super chain ${viaParent.next()}")
    println("super calls: OK")

    // Generic parents: per-T monomorphized fields, inherited generic methods,
    // polymorphic dispatch through Box<Int>, open generic direct + child.
    val ib = IntBox(21)
    if (ib.value != 21 || ib.describe() != 42 || ib.unwrap() != 21) error("FAIL IntBox")
    val sbx = StrBox("hello")
    if (sbx.describe() != 5 || sbx.unwrap() != "hello") error("FAIL StrBox")
    val bx: Box<Int> = IntBox(3)
    if (bx.describe() != 6 || bx.value != 3) error("FAIL Box<Int> view")
    val pr = Pair2<Int>(1, 2)
    if (pr.first != 1 || pr.second != 2) error("FAIL Pair2 direct")
    val ip = IntPair(7, 8)
    ip.second = 9
    if (ip.first != 7 || ip.second != 9) error("FAIL IntPair")
    println("generic parents: OK")

    // Exceptions defined by extending Exception - caught at every level.
    var t = 0
    try {
        throw ConfigError("bad config")
    } catch (e: ConfigError) {
        if (e.message != "bad config" || e.code != 78) error("FAIL ConfigError fields")
        t += 1
    }
    try {
        throw ConfigError("again")
    } catch (e: AppError) {
        t += 10
    }
    try {
        throw AppError("direct", 5)
    } catch (e: Exception) {
        t += 100
    }
    if (t != 111) error("FAIL exception inheritance t=$t")
    println("class-based exceptions: OK")

    println("ALL OK")
}

// ==================
// MARK: InheritedDefaults
// ==================

/** A default argument that names an earlier parameter, of a member called through an object that inherits it. */

open class Codec(val name: String) {
	fun decode(source: CharSequence, startIndex: Int = 0, endIndex: Int = source.length): String = source.substring(startIndex, endIndex) + name
	companion object Default : Codec("d")
}

@Test
fun inheritedDefaults() {
	println(Codec.decode("abc"))
	for (c in listOf(Codec.Default, Codec("x"))) {
		try { println(c.decode("xyz")) } catch (e: IllegalArgumentException) { println("no") }
	}
}
