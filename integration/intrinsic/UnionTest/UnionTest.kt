package UnionTest

sealed interface Shape {
	data class Circle(val radius: Float) : Shape
	data class Rect(val w: Float, val h: Float) : Shape
	data class Triangle(val a: Float, val b: Float, val c: Float) : Shape
}

fun printShape(s: Shape) {
	when (s) {
		is Shape.Circle -> println("circle r=${s.radius}")
		is Shape.Rect -> println("rect ${s.w}x${s.h}")
		is Shape.Triangle -> println("triangle")
	}
}

sealed interface Result<T> {
	data class Ok<T>(val value: T) : Result<T>
	data class Err<T>(val message: String) : Result<T>
}

fun divide(a: Int, b: Int): Result<Int> {
	if (b == 0) return Result.Err<Int>("division by zero")
	return Result.Ok<Int>(a / b)
}

fun safeSqrt(x: Float): Result<Float> {
	if (x < 0.0f) return Result.Err<Float>("negative input")
	return Result.Ok<Float>(x)
}

fun main() {
	// Basic sealed interface construction and is-check
	val c: Shape = Shape.Circle(3.14f)
	val r: Shape = Shape.Rect(10.0f, 20.0f)
	if (c is Shape.Circle) {
		println("circle: ${c.radius}")
	} else {
		error("FAIL: c should be Circle")
	}
	if (r is Shape.Rect) {
		println("rect: ${r.w} ${r.h}")
	} else {
		error("FAIL: r should be Rect")
	}

	// !is check
	if (c !is Shape.Rect) {
		println("negated ok")
	} else {
		error("FAIL: c should not be Rect")
	}

	// when + is smart cast
	when (c) {
		is Shape.Circle -> println("when circle: ${c.radius}")
		is Shape.Rect -> println("when rect")
		is Shape.Triangle -> println("when triangle")
	}

	// Function receiving sealed interface
	printShape(c)
	printShape(r)

	// Three-variant sealed interface
	val t: Shape = Shape.Triangle(3.0f, 4.0f, 5.0f)
	if (t is Shape.Triangle) {
		println("triple: ${t.a}")
	} else {
		error("FAIL: t should be Triangle")
	}

	// Result-like pattern: divide with sealed interface
	val d1 = divide(10, 2)
	val d2 = divide(10, 0)
	if (d1 is Result.Ok) {
		if (d1.value != 5) error("FAIL: divide 10/2 should be 5")
		println("divide ok: ${d1.value}")
	} else {
		error("FAIL: divide 10/2 should succeed")
	}
	if (d2 is Result.Err) {
		println("divide err: ${d2.message}")
	} else {
		error("FAIL: divide 10/0 should fail")
	}

	// Result-like pattern: safeSqrt
	val s1 = safeSqrt(4.0f)
	val s2 = safeSqrt(-1.0f)
	if (s1 is Result.Ok) {
		println("sqrt ok: ${s1.value}")
	} else {
		error("FAIL: safeSqrt(4) should succeed")
	}
	if (s2 is Result.Err) {
		println("sqrt err: ${s2.message}")
	} else {
		error("FAIL: safeSqrt(-1) should fail")
	}

	// When-style dispatch on result
	when (d1) {
		is Result.Ok -> println("result: value=${d1.value}")
		is Result.Err -> println("result: error=${d1.message}")
	}

	println("done")
}
