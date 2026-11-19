package ktc2test.objects.classinstancechecks

import kotlin.test.Test
import kotlin.reflect.cast
import kotlin.reflect.safeCast

// `KClass.isInstance` and `cast` for classes and interfaces: the program's own interfaces, ones
// inherited through a superclass or another interface, generic ones, and the library's.

interface Named {
	val name: String
}

interface Greeter : Named {
	fun greet() = "hello $name"
}

interface Marker

open class Base(override val name: String) : Greeter

class Derived(name: String) : Base(name), Marker

class Plain

interface Box<T> {
	fun get(): T
}

class IntBox(val value: Int) : Box<Int>, Comparable<IntBox> {
	override fun get() = value
	override fun compareTo(other: IntBox) = value.compareTo(other.value)
}

@Test
fun main() {
	val values: List<Any> = listOf(Base("b"), Derived("d"), Plain(), IntBox(1), "text", 42, listOf(1), mutableMapOf(1 to 2))
	val classes = listOf(Named::class, Greeter::class, Marker::class, Base::class, Derived::class, Box::class, Comparable::class, CharSequence::class, List::class, Collection::class, Iterable::class, Map::class, Number::class, Any::class)
	for (k in classes) {
		println("${k.simpleName}: " + values.map { if (k.isInstance(it)) 1 else 0 }.joinToString(""))
	}
	println(Greeter::class.cast(Derived("x")).greet())
	println(runCatching { Marker::class.cast(Base("y")) }.exceptionOrNull()?.let { it::class.simpleName })
	println(Named::class.safeCast(Plain()))
	println(values.filter { Named::class.isInstance(it) }.map { (it as Named).name })
}
