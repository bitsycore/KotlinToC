package MemberInfixTest

// Regression test for B12: a member `infix fun` must (1) be registered so `a name b` parses as an
// infix call (not three dangling statements) and (2) dispatch through the method, not a raw C
// binary operator. main returns a non-zero exit code if any check fails.

class Vec(val x: Int, val y: Int) {
	infix fun combine(o: Vec): Vec = Vec(x + o.x, y + o.y)
	infix fun dot(o: Vec): Int = x * o.x + y * o.y
	// A member infix named like the built-in bitwise `and` — the member must win for Vec receivers,
	// while `x and o.x` below stays the integer bitwise op (Int is not a user class).
	infix fun and(o: Vec): Vec = Vec(x and o.x, y and o.y)
}

fun main(): Int {
	var vOk = true
	val a = Vec(1, 2)
	val b = Vec(3, 4)

	// Infix method returning the class.
	val c = a combine b
	vOk = vOk && c.x == 4 && c.y == 6

	// Infix method returning a primitive.
	val d = a dot b
	vOk = vOk && d == 11            // 1*3 + 2*4

	// Member `and` wins over the built-in bitwise infix for a user receiver.
	val e = Vec(6, 5) and Vec(3, 6)
	vOk = vOk && e.x == 2 && e.y == 4   // 6&3=2, 5&6=4

	// Sanity: integer bitwise `and` still works on primitives.
	vOk = vOk && (6 and 3) == 2

	if (!vOk) {
		println("MemberInfixTest FAILED")
		return 1
	}
	println("MemberInfixTest OK")
	return 0
}
