package ktc2test.text.templates

import kotlin.test.Test

// ==================
// MARK: StringTemplate
// ==================

data class Vec2(val x: Float, val y: Float)
data class Color(val r: Int, val g: Int, val b: Int)

inline fun greet(name: String): String = "Hello, $name!"

@Test
fun stringTemplate() {
	// Simple variable interpolation
	val name = "World"
	val age = 42
	val t1 = "Hello, $name!"
	if (t1 != "Hello, World!") error("FAIL t1: $t1")
	println(t1)

	// Int interpolation
	val t2 = "Age: $age"
	if (t2 != "Age: 42") error("FAIL t2: $t2")
	println(t2)

	// Expression in braces
	val t3 = "Next year: ${age + 1}"
	if (t3 != "Next year: 43") error("FAIL t3: $t3")
	println(t3)

	// Data class toString
	val v = Vec2(3.0f, 4.0f)
	println("Position: $v")

	// String property in template
	val s = "hello"
	val t5 = "Length of '$s' is ${s.length}"
	if (t5 != "Length of 'hello' is 5") error("FAIL t5: $t5")
	println(t5)

	// Multiple vars in one template
	val a = 10
	val b = 20
	val t6 = "$a + $b = ${a + b}"
	if (t6 != "10 + 20 = 30") error("FAIL t6: $t6")
	println(t6)

	// Boolean interpolation
	val flag = true
	val t7 = "flag=$flag"
	if (t7 != "flag=true") error("FAIL t7: $t7")
	println(t7)

	// Nested expression
	val x = 5
	val t8 = "result=${x * x + 1}"
	if (t8 != "result=26") error("FAIL t8: $t8")
	println(t8)

	// Consecutive templates
	val first = "Alice"
	val last = "Smith"
	val full = "$first $last"
	if (full != "Alice Smith") error("FAIL full: $full")
	println(full)

	// Template with no interpolation (literal)
	val lit = "no vars here"
	if (lit != "no vars here") error("FAIL literal")
	println(lit)

	// Empty string parts
	val n = 42
	val t9 = "$n"
	if (t9 != "42") error("FAIL bare var: $t9")
	println("bare var: $t9")

	// Method call in template
	val greeting = greet("KTC")
	if (greeting != "Hello, KTC!") error("FAIL greeting: $greeting")
	println(greeting)

	// Float formatting
	val pi = 3.14f
	val t10 = "pi=$pi"
	println(t10)

	// Multiple data class fields
	val c = Color(255, 128, 0)
	val t11 = "rgb(${c.r}, ${c.g}, ${c.b})"
	if (t11 != "rgb(255, 128, 0)") error("FAIL color: $t11")
	println(t11)

	// Computed expression in template
	val score = 85
	val passed = score >= 60
	val t12 = "Score: $score passed=$passed"
	println(t12)

	println("ALL OK")
}

// ==================
// MARK: TemplateEval
// ==================

// Regression test for B8: a side-effecting interpolation `${f()}` must run f() exactly once.
// Before the fix the two-pass (count+fill) template lowering emitted each append twice, and the
// nullable append embedded the value twice (tag check + value), so f() ran 2–4×.
// main returns a non-zero exit code if any check fails.

var counter = 0

fun nextInt(): Int { counter = counter + 1; return counter }

fun nextOpt(): Int? { counter = counter + 1; return counter }

fun nextStr(): String { counter = counter + 1; return "s" }

fun mainImpl(): Int {
	// Nullable interpolation in a String template (nullable double-embed path).
	counter = 0
	val a = "x=${nextOpt()}"
	if (counter != 1) { println("FAIL nullable-template: $counter"); return 1 }

	// Two nullable interpolations (forces the two-pass count/fill fallback).
	counter = 0
	val b = "p=${nextOpt()} q=${nextOpt()}"
	if (counter != 2) { println("FAIL two-pass: $counter"); return 2 }

	// println template path (uses StrBuf because of the nullable part).
	counter = 0
	println("r=${nextOpt()}")
	if (counter != 1) { println("FAIL println-template: $counter"); return 3 }

	// Non-null Int interpolation (single-pass computed-size path).
	counter = 0
	val c = "n=${nextInt()}"
	if (counter != 1) { println("FAIL int-template: $counter"); return 4 }

	// String interpolation (computed-size path with runtime .len).
	counter = 0
	val d = "v=${nextStr()}!"
	if (counter != 1) { println("FAIL str-template: $counter"); return 5 }

	// Sanity-check the produced text too.
	if (a != "x=1") { println("FAIL text a=$a"); return 6 }
	if (b != "p=1 q=2") { println("FAIL text b=$b"); return 7 }

	println("TemplateEvalTest OK ($a | $b | $c | $d)")
	return 0
}

@Test
fun templateEval() {
	mainImpl()
}

// ==================
// MARK: TemplateArguments
// ==================

/** A text made by a template and an object of a class handed to the same generic function: the text stays a text there. */

fun <T> check(expected: T, actual: T) {
	if (expected != actual) println("differ: <$expected> <$actual>") else println("same: <$expected>")
}

@Test
fun templateArguments() {
	val c = 'x'
	check(c.toString(), "x")
	check(Pair('a', 'b'), Pair('a', 'b'))
}
