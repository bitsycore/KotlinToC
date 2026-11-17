/** Properties delegated to property references: of an object, of a top-level property, of `this`. */
open class Data(val stringVal: String, var intVar: Int)

val data = Data("bound string", 42)
var topVar: Long = 7

val valBoundVal by data::stringVal
val valBoundVar by data::intVar
val valTopLevel by ::topVar

class Wrapper : Data("member", -1) {
	val shared by ::topVar
}

fun main() {
	println(valBoundVal)
	data.intVar += 1
	println(valBoundVar)
	topVar = 9
	println(valTopLevel)
	val w = Wrapper()
	println(w.shared)
	val length = String::length
	println(length.get("four"))
	more()
}

var count = 1
var varTopLevel by ::count
var varBound by data::intVar

class Counter(var value: Int)

class Alias(val counter: Counter) {
	var alias by counter::value
}

fun more() {
	varTopLevel = 5
	println(count)
	varBound = 100
	println(data.intVar)
	val c = Counter(3)
	val a = Alias(c)
	a.alias += 4
	println(c.value)
	val ref = Counter::value
	ref.set(c, 20)
	println(ref.get(c))
	val top = ::count
	top.set(8)
	println(top.get() + count)
}
