// Inheritance with generics as ordinary code writes it: abstract generic bases, type parameters
// fixed by a subclass, bounds, interfaces with default methods over a type parameter, variance,
// overrides that narrow the return type, generic methods in generic classes, star projections.

package ktc2test.generics.generichierarchies

import kotlin.test.Test

interface Shape {
	val area: Double
	fun describe(): String = "${this::class.simpleName} of area ${area.toInt()}"
}

data class Square(val side: Int) : Shape {
	override val area get() = (side * side).toDouble()
}

data class Rect(val w: Int, val h: Int) : Shape {
	override val area get() = (w * h).toDouble()
	override fun describe() = "rect ${w}x$h"
}

abstract class Repository<K : Comparable<K>, V> {
	protected val items = LinkedHashMap<K, V>()
	abstract fun keyOf(value: V): K
	open fun add(value: V): Repository<K, V> {
		items[keyOf(value)] = value
		return this
	}
	fun get(key: K): V? = items[key]
	fun sortedKeys(): List<K> = items.keys.sorted()
	open fun all(): List<V> = items.values.toList()
}

class ShapeRepository : Repository<String, Shape>() {
	override fun keyOf(value: Shape) = value.describe()
	override fun add(value: Shape): ShapeRepository {
		super.add(value)
		return this
	}
	fun totalArea() = items.values.sumOf { it.area }
}

open class NumberedRepository<V> : Repository<Int, V>() {
	private var next = 0
	override fun keyOf(value: V): Int = next++
	override fun all(): List<V> = super.all().reversed()
}

class Names : NumberedRepository<String>() {
	fun longest() = items.values.maxByOrNull { it.length }
}

interface Container<out T> {
	fun first(): T
	fun all(): List<T>
	fun <R> mapped(f: (T) -> R): List<R> = all().map(f)
	fun count(): Int = all().size
}

interface Sink<in T> {
	fun put(value: T)
}

class Box<T>(private val values: MutableList<T>) : Container<T>, Sink<T> {
	override fun first() = values.first()
	override fun all(): List<T> = values
	override fun put(value: T) {
		values += value
	}
	override fun toString() = "Box$values"
}

fun fill(sink: Sink<Int>, n: Int) {
	for (i in 1..n) sink.put(i * 10)
}

fun describeAll(container: Container<Shape>) = container.mapped { it.describe() }

fun sumAll(container: Container<Number>) = container.all().sumOf { it.toDouble() }

abstract class Node<T : Node<T>> {
	val children = ArrayList<T>()
	abstract fun self(): T
	fun add(child: T): T {
		children += child
		return self()
	}
	fun depth(): Int = 1 + (children.maxOfOrNull { it.depth() } ?: 0)
}

class Folder(val name: String) : Node<Folder>() {
	override fun self() = this
	fun names(): List<String> = listOf(name) + children.flatMap { it.names() }
}

open class Animal(val name: String) : Comparable<Animal> {
	override fun compareTo(other: Animal) = name.compareTo(other.name)
	open fun sound() = "..."
	override fun toString() = "$name says ${sound()}"
}

class Dog(name: String) : Animal(name) {
	override fun sound() = "woof"
}

class Cat(name: String) : Animal(name) {
	override fun sound() = "meow"
}

fun <T : Comparable<T>> maxOf3(a: T, b: T, c: T): T = maxOf(a, maxOf(b, c))

fun <T> copyInto(from: List<T>, to: MutableList<in T>) {
	to.addAll(from)
}

fun printAny(list: List<*>) = list.joinToString { "${it?.let { v -> v::class.simpleName }}:$it" }

abstract class Pipeline<I, O> {
	abstract fun run(input: I): O
	fun <P> then(next: Pipeline<O, P>): Pipeline<I, P> {
		val first = this
		return object : Pipeline<I, P>() {
			override fun run(input: I) = next.run(first.run(input))
		}
	}
}

class Parse : Pipeline<String, Int>() {
	override fun run(input: String) = input.trim().toInt()
}

class Double2 : Pipeline<Int, Int>() {
	override fun run(input: Int) = input * 2
}

class Show<T> : Pipeline<T, String>() {
	override fun run(input: T) = "<$input>"
}

sealed class Result2<out T> {
	data class Ok<T>(val value: T) : Result2<T>()
	data class Err(val message: String) : Result2<Nothing>()

	fun <R> map(f: (T) -> R): Result2<R> = when (this) {
		is Ok -> Ok(f(value))
		is Err -> this
	}

	fun getOr(default: @UnsafeVariance T): T = when (this) {
		is Ok -> value
		is Err -> default
	}
}

fun parse(text: String): Result2<Int> = text.toIntOrNull()?.let { Result2.Ok(it) } ?: Result2.Err("not a number: $text")

interface Visitor<R> {
	fun visitNum(n: Int): R
	fun visitAdd(a: Expr, b: Expr): R
}

interface Expr {
	fun <R> accept(v: Visitor<R>): R
}

class NumE(val n: Int) : Expr {
	override fun <R> accept(v: Visitor<R>) = v.visitNum(n)
}

class AddE(val a: Expr, val b: Expr) : Expr {
	override fun <R> accept(v: Visitor<R>) = v.visitAdd(a, b)
}

object Evaluator : Visitor<Int> {
	override fun visitNum(n: Int) = n
	override fun visitAdd(a: Expr, b: Expr) = a.accept(this) + b.accept(this)
}

object Printer : Visitor<String> {
	override fun visitNum(n: Int) = n.toString()
	override fun visitAdd(a: Expr, b: Expr) = "(${a.accept(this)} + ${b.accept(this)})"
}

open class Base<T>(val value: T) {
	open fun show(): String = "Base($value)"
	override fun equals(other: Any?) = other is Base<*> && other.value == value
	override fun hashCode() = value.hashCode()
}

class Derived(value: Int) : Base<Int>(value) {
	override fun show() = "Derived(${value + 1}) from ${super.show()}"
}

@Test
fun main() {
	val shapes = ShapeRepository().add(Square(3)).add(Rect(2, 5)).add(Square(1))
	println(shapes.sortedKeys())
	println(shapes.totalArea())
	println(shapes.get("rect 2x5"))
	val repository: Repository<String, Shape> = shapes
	println(repository.all().map { it.area })

	val names = Names()
	names.add("ann").add("bartholomew").add("cy")
	println(names.all())
	println(names.longest())
	println(names.get(1))

	val box = Box(mutableListOf<Int>())
	fill(box, 3)
	val numbers: Container<Number> = box
	println(sumAll(numbers))
	println(numbers.mapped { it.toInt() + 1 })
	println(box.count())
	val anySink: Sink<Any> = object : Sink<Any> {
		override fun put(value: Any) = println("sink got $value")
	}
	fill(anySink, 2)
	println(describeAll(Box(mutableListOf(Square(2), Rect(1, 3)))))

	val root = Folder("root")
	val docs = Folder("docs")
	root.add(docs).add(Folder("src"))
	docs.add(Folder("a")).add(Folder("b"))
	println(root.names())
	println(root.depth())

	val animals = listOf(Dog("rex"), Cat("tom"), Dog("ace"))
	println(animals.sorted())
	println(maxOf3(Dog("b"), Cat("c"), Dog("a")))
	println(maxOf3("x", "z", "y"))
	val pets = mutableListOf<Animal>(Cat("kit"))
	copyInto(listOf(Dog("fido")), pets)
	val anything = mutableListOf<Any>()
	copyInto(pets, anything)
	println(anything)
	println(printAny(listOf(1, "a", 2.5, null, 'c', 3L)))

	val pipeline = Parse().then(Double2()).then(Double2()).then(Show())
	println(pipeline.run(" 21 "))

	println(parse("12").map { it * 3 }.getOr(0))
	println(parse("x").map { it * 3 }.getOr(-1))
	println(parse("x"))
	println(listOf("1", "2", "z").map(::parse).map { r -> r.map { "#$it" } })

	val expr = AddE(NumE(1), AddE(NumE(2), NumE(3)))
	println(expr.accept(Printer) + " = " + expr.accept(Evaluator))

	val bases: List<Base<*>> = listOf(Base("s"), Derived(4), Base(4))
	println(bases.map { it.show() })
	println(bases[1] == bases[2])
	println(bases.toSet().size)
}
