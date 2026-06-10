package SimpleUnionTest

// Non-generic @SimpleUnion sealed interface
@SimpleUnion
sealed interface Shape {
	data class Circle(val radius: Float) : Shape
	data class Rect(val w: Float, val h: Float) : Shape
}

// Generic @SimpleUnion sealed interface
@SimpleUnion
sealed interface Option<T> {
	data class Some<T>(val value: T) : Option<T>
	data class None<T>(val unit: Int = 0) : Option<T>
}

fun printShape(s: Shape) {
	when (s) {
		is Shape.Circle -> println("circle r=${s.radius}")
		is Shape.Rect -> println("rect ${s.w}x${s.h}")
	}
}

fun tryDivide(a: Int, b: Int): Option<Int> {
	if (b == 0) return Option.None<Int>()
	return Option.Some<Int>(a / b)
}

fun main() {
	// ── Basic construction and is-check ──
	val c: Shape = Shape.Circle(3.14f)
	val r: Shape = Shape.Rect(10.0f, 20.0f)
	if (c is Shape.Circle) {
		println("circle: ${c.radius}")
	} else {
		fatalError("FAIL: c should be Circle")
	}
	if (r is Shape.Rect) {
		println("rect: ${r.w} ${r.h}")
	} else {
		fatalError("FAIL: r should be Rect")
	}

	// ── Negated is-check ──
	if (c !is Shape.Rect) {
		println("negated ok")
	} else {
		fatalError("FAIL: c should not be Rect")
	}

	// ── when + smart-cast ──
	when (c) {
		is Shape.Circle -> println("when circle: ${c.radius}")
		is Shape.Rect -> println("when rect")
	}

	// ── Function receiving @SimpleUnion ──
	printShape(c)
	printShape(r)

	// ── hashCode dispatch (Any method on @SimpleUnion) ──
	val h1 = c.hashCode()
	val h2 = r.hashCode()
	if (h1 != h2) {
		println("hashCodes differ: ok")
	} else {
		fatalError("FAIL: different shapes should have different hashCodes")
	}

	// ── equals dispatch ──
	val c2: Shape = Shape.Circle(3.14f)
	if (c == c2) {
		println("equals same: ok")
	} else {
		fatalError("FAIL: identical circles should be equal")
	}
	if (c != r) {
		println("equals diff: ok")
	} else {
		fatalError("FAIL: circle != rect")
	}

	// ── toString dispatch ──
	println("toString: $c")

	// ── if-expression smart-cast ──
	val radius = if (c is Shape.Circle) c.radius else -1.0f
	if (radius > 3.0f) {
		println("if-expr cast: $radius")
	} else {
		fatalError("FAIL: if-expr smart-cast")
	}

	// ── Generic @SimpleUnion ──
	val s1 = tryDivide(10, 2)
	val s2 = tryDivide(10, 0)
	if (s1 is Option.Some) {
		println("divide ok: ${s1.value}")
	} else {
		fatalError("FAIL: 10/2 should succeed")
	}
	if (s2 is Option.None) {
		println("divide none: ok")
	} else {
		fatalError("FAIL: 10/0 should be None")
	}

	// ── Generic when ──
	when (s1) {
		is Option.Some -> println("when some: ${s1.value}")
		is Option.None -> println("when none")
	}

	// ── Multiple instantiations ──
	val oi: Option<Int> = Option.Some<Int>(42)
	val of: Option<Float> = Option.Some<Float>(2.71f)
	if (oi is Option.Some && of is Option.Some) {
		println("multi: ${oi.value} ${of.value}")
	} else {
		fatalError("FAIL: multi instantiation")
	}

	println("done")
}
