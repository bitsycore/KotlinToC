package AnonObjectTest

interface Greeter {
	fun greet(): Int
}

interface Shape {
	fun area(): Float
	fun name(): String
}

fun useGreeter(g: Greeter): Int = g.greet()

fun printShape(s: Shape) {
	println("${s.name()}: ${s.area()}")
}

fun main() {
	// Single-method anonymous object
	val g: Greeter = object : Greeter {
		override fun greet(): Int = 42
	}
	val result = useGreeter(g)
	if (result != 42) fatalError("FAIL greet returned $result")
	println("greet: $result")

	// Multi-method anonymous object
	val s: Shape = object : Shape {
		override fun area(): Float = 3.14f
		override fun name(): String = "AnonCircle"
	}
	printShape(s)
	if (s.area() < 3.13f || s.area() > 3.15f) fatalError("FAIL area: ${s.area()}")
	if (s.name() != "AnonCircle") fatalError("FAIL name: ${s.name()}")

	// Second anonymous object of the same interface (different synthetic name)
	val s2: Shape = object : Shape {
		override fun area(): Float = 9.0f
		override fun name(): String = "AnonSquare"
	}
	if (s2.area() != 9.0f) fatalError("FAIL s2 area: ${s2.area()}")
	if (s2.name() != "AnonSquare") fatalError("FAIL s2 name: ${s2.name()}")
	println("${s2.name()}: ${s2.area()}")

	// Pass anonymous object directly to function (no intermediate variable)
	val directResult = useGreeter(object : Greeter {
		override fun greet(): Int = 99
	})
	if (directResult != 99) fatalError("FAIL direct greet: $directResult")
	println("direct greet: $directResult")

	println("done")
}
