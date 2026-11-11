package basic

import ktc2.Struct

const val LIMIT = 10
val base = 5
var counter = base * 2
val doubled get() = counter * 2
var clamped = 0
	set(value) {
		field = if (value > LIMIT) LIMIT else value
	}
val ratio = 0.25

@Struct
class Size(val w: Int, val h: Int)

val screen = Size(base * 64, 200)

fun trace(tag: Int): Int {
	println("arg $tag")
	return tag
}

fun scale(x: Int, factor: Int = 2, offset: Int = x + factor): Int = x * factor + offset

fun area(s: Size = screen, border: Int = counter): Int = (s.w - border) * (s.h - border)

fun countdown(from: Int, step: Int = 1): Int = if (from <= 0) 0 else 1 + countdown(from - step)

fun next(): Int {
	counter++
	return counter
}

fun main() {
	println("$LIMIT $base $counter $doubled $ratio")
	counter += LIMIT
	println(counter + doubled)
	clamped = 7
	println(clamped)
	clamped = 70
	println(clamped)
	println("${screen.w}x${screen.h}")

	// A global read is ordered against a call that changes it.
	println(counter + next())
	println(next() + counter)

	println(scale(3))
	println(scale(3, 4))
	println(scale(3, offset = 1))
	println(scale(factor = trace(1), x = trace(2)))
	println(area())
	println(area(Size(10, 10), 2))
	println(area(border = 0))
	println(countdown(5))
	println(countdown(6, 2))
}
