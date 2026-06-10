package StringTemplateTest

data class Vec2(val x: Float, val y: Float)
data class Color(val r: Int, val g: Int, val b: Int)

inline fun greet(name: String): String = "Hello, $name!"

fun main() {
	// Simple variable interpolation
	val name = "World"
	val age = 42
	val t1 = "Hello, $name!"
	if (t1 != "Hello, World!") fatalError("FAIL t1: $t1")
	println(t1)

	// Int interpolation
	val t2 = "Age: $age"
	if (t2 != "Age: 42") fatalError("FAIL t2: $t2")
	println(t2)

	// Expression in braces
	val t3 = "Next year: ${age + 1}"
	if (t3 != "Next year: 43") fatalError("FAIL t3: $t3")
	println(t3)

	// Data class toString
	val v = Vec2(3.0f, 4.0f)
	println("Position: $v")

	// String property in template
	val s = "hello"
	val t5 = "Length of '$s' is ${s.length}"
	if (t5 != "Length of 'hello' is 5") fatalError("FAIL t5: $t5")
	println(t5)

	// Multiple vars in one template
	val a = 10
	val b = 20
	val t6 = "$a + $b = ${a + b}"
	if (t6 != "10 + 20 = 30") fatalError("FAIL t6: $t6")
	println(t6)

	// Boolean interpolation
	val flag = true
	val t7 = "flag=$flag"
	if (t7 != "flag=true") fatalError("FAIL t7: $t7")
	println(t7)

	// Nested expression
	val x = 5
	val t8 = "result=${x * x + 1}"
	if (t8 != "result=26") fatalError("FAIL t8: $t8")
	println(t8)

	// Consecutive templates
	val first = "Alice"
	val last = "Smith"
	val full = "$first $last"
	if (full != "Alice Smith") fatalError("FAIL full: $full")
	println(full)

	// Template with no interpolation (literal)
	val lit = "no vars here"
	if (lit != "no vars here") fatalError("FAIL literal")
	println(lit)

	// Empty string parts
	val n = 42
	val t9 = "$n"
	if (t9 != "42") fatalError("FAIL bare var: $t9")
	println("bare var: $t9")

	// Method call in template
	val greeting = greet("KTC")
	if (greeting != "Hello, KTC!") fatalError("FAIL greeting: $greeting")
	println(greeting)

	// Float formatting
	val pi = 3.14f
	val t10 = "pi=$pi"
	println(t10)

	// Multiple data class fields
	val c = Color(255, 128, 0)
	val t11 = "rgb(${c.r}, ${c.g}, ${c.b})"
	if (t11 != "rgb(255, 128, 0)") fatalError("FAIL color: $t11")
	println(t11)

	// Computed expression in template
	val score = 85
	val passed = score >= 60
	val t12 = "Score: $score passed=$passed"
	println(t12)

	println("ALL OK")
}
