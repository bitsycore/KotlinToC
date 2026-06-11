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

    // super.method() through multi-level chains + super.prop.
    if (Doubling(3).next() != 8) fatalError("FAIL Doubling.next")
    val dp = DoublingPlus(3)
    if (dp.next() != 13) fatalError("FAIL DoublingPlus.next ${dp.next()}")
    if (dp.baseViaSuper() != 3) fatalError("FAIL super.prop")
    val viaParent: Counter = DoublingPlus(10)
    if (viaParent.next() != 27) fatalError("FAIL virtual super chain ${viaParent.next()}")
    println("super calls: OK")

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
