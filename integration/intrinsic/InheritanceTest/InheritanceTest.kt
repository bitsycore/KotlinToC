package test

// Integration test for class inheritance: open/abstract/sealed classes,
// data + method inheritance, virtual dispatch through parent-typed values,
// multi-level chains, sealed-class when, and exceptions via class extension.

// ==================
// MARK: Open class hierarchy
// ==================

open class Animal(val name: String, val legs: Int = 4) {
    open fun sound(): Int = 0
    fun describe(): Int = name.length * 10 + legs   // final — inherited as-is
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

abstract class Shape(val sides: Int) {
    abstract fun area(): Float
    fun cornerCount(): Int = sides            // concrete helper, inherited
    open fun isRound(): Boolean = false
}

class Rect(val w: Float, val h: Float) : Shape(4) {
    override fun area(): Float = w * h
}

class Circle(val r: Float) : Shape(0) {
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

fun main() {
    // Data inheritance: fields flow down the chain (with defaults).
    val d = Dog("rex", "lab")
    if (d.name != "rex") fatalError("FAIL dog.name")
    if (d.legs != 4) fatalError("FAIL dog.legs default")
    if (d.breed != "lab") fatalError("FAIL dog.breed")
    val b = Bird("tweety")
    if (b.legs != 2) fatalError("FAIL bird.legs super-arg")
    val sn = Snake("sss")
    if (sn.legs != 0 || sn.name != "sss") fatalError("FAIL named super-args")
    val sp = Spider("web")
    if (sp.legs != 8 || sp.name != "web") fatalError("FAIL mixed super-args")
    println("data inheritance: OK")

    // Method inheritance + override, on concrete values.
    if (d.sound() != 1) fatalError("FAIL dog.sound")
    if (d.describe() != 34) fatalError("FAIL dog.describe inherited")
    val p = Puppy("io")
    if (p.sound() != 2) fatalError("FAIL puppy.sound")
    if (p.breed != "unknown") fatalError("FAIL puppy.breed via Dog default chain")
    if (p.describe() != 24) fatalError("FAIL puppy.describe two levels up")
    println("method inheritance: OK")

    // Virtual dispatch through a parent-typed value (fat value, vtable).
    val animals0: Animal = Dog("a", "x")
    val animals1: Animal = Puppy("bb")
    val animals2: Animal = Animal("ccc")     // direct open-class instance
    val animals3: Animal = Bird("dddd")
    if (animals0.sound() != 1) fatalError("FAIL virtual dog")
    if (animals1.sound() != 2) fatalError("FAIL virtual puppy")
    if (animals2.sound() != 0) fatalError("FAIL virtual base")
    if (animals3.sound() != 0) fatalError("FAIL virtual bird inherits base")
    if (animals1.describe() != 24) fatalError("FAIL virtual describe")
    if (animals3.name != "dddd") fatalError("FAIL property via parent type")
    println("virtual dispatch: OK")

    // is / !is on parent-typed values, down the chain.
    if (animals1 !is Puppy) fatalError("FAIL is Puppy")
    if (animals1 !is Dog) fatalError("FAIL puppy is Dog")
    if (animals0 is Puppy) fatalError("FAIL dog is not Puppy")
    println("is-checks: OK")

    // Abstract class: dispatch + inherited concrete + open default.
    val s0: Shape = Rect(2.0f, 3.0f)
    val s1: Shape = Circle(1.0f)
    if (s0.area() != 6.0f) fatalError("FAIL rect area")
    if (s1.area() < 3.0f) fatalError("FAIL circle area")
    if (s0.cornerCount() != 4) fatalError("FAIL inherited cornerCount")
    if (s0.isRound()) fatalError("FAIL rect isRound default")
    if (!s1.isRound()) fatalError("FAIL circle isRound override")
    println("abstract class: OK")

    // Sealed class: exhaustive when over subclasses.
    if (nodeWeight(Leaf(1, 7)) != 7) fatalError("FAIL leaf weight")
    if (nodeWeight(Branch(2, 3)) != 300) fatalError("FAIL branch weight")
    println("sealed when: OK")

    // Inherited var props: write through concrete AND parent-typed values.
    val hg = HalfGauge()
    hg.level = 7
    if (hg.level != 7) fatalError("FAIL concrete var write")
    val g: Gauge = HalfGauge()
    g.level = 33
    if (g.level != 33) fatalError("FAIL var write via parent ${g.level}")
    g.fill()
    if (g.level != 50) fatalError("FAIL fill via parent ${g.level}")
    println("var props: OK")

    // super.method() through multi-level chains + super.prop.
    if (Doubling(3).next() != 8) fatalError("FAIL Doubling.next")
    val dp = DoublingPlus(3)
    if (dp.next() != 13) fatalError("FAIL DoublingPlus.next ${dp.next()}")
    if (dp.baseViaSuper() != 3) fatalError("FAIL super.prop")
    val viaParent: Counter = DoublingPlus(10)
    if (viaParent.next() != 27) fatalError("FAIL virtual super chain ${viaParent.next()}")
    println("super calls: OK")

    // Generic parents: per-T monomorphized fields, inherited generic methods,
    // polymorphic dispatch through Box<Int>, open generic direct + child.
    val ib = IntBox(21)
    if (ib.value != 21 || ib.describe() != 42 || ib.unwrap() != 21) fatalError("FAIL IntBox")
    val sbx = StrBox("hello")
    if (sbx.describe() != 5 || sbx.unwrap() != "hello") fatalError("FAIL StrBox")
    val bx: Box<Int> = IntBox(3)
    if (bx.describe() != 6 || bx.value != 3) fatalError("FAIL Box<Int> view")
    val pr = Pair2<Int>(1, 2)
    if (pr.first != 1 || pr.second != 2) fatalError("FAIL Pair2 direct")
    val ip = IntPair(7, 8)
    ip.second = 9
    if (ip.first != 7 || ip.second != 9) fatalError("FAIL IntPair")
    println("generic parents: OK")

    // Exceptions defined by extending Exception — caught at every level.
    var t = 0
    try {
        throw ConfigError("bad config")
    } catch (e: ConfigError) {
        if (e.message != "bad config" || e.code != 78) fatalError("FAIL ConfigError fields")
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
    if (t != 111) fatalError("FAIL exception inheritance t=$t")
    println("class-based exceptions: OK")

    println("ALL OK")
}
