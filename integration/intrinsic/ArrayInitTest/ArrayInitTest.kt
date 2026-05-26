package ArrayInitTest

fun main(): Int {

	// ── Array<Int>(n) { init } ────────────────────────────────────────
	val squares = Array<Int>(5) { it * it }
	if (squares[0] != 0) error("FAIL squares[0]")
	println("OK: squares[0] = ${squares[0]}")
	if (squares[1] != 1) error("FAIL squares[1]")
	println("OK: squares[1] = ${squares[1]}")
	if (squares[2] != 4) error("FAIL squares[2]")
	println("OK: squares[2] = ${squares[2]}")
	if (squares[3] != 9) error("FAIL squares[3]")
	println("OK: squares[3] = ${squares[3]}")
	if (squares[4] != 16) error("FAIL squares[4]")
	println("OK: squares[4] = ${squares[4]}")

	// ── Array<Float>(n) { init } ──────────────────────────────────────
	val floats = Array<Float>(4) { it.toFloat() * 0.5f }
	if (floats[0] != 0.0f) error("FAIL floats[0]")
	println("OK: floats[0] = ${floats[0]}")
	if (floats[2] != 1.0f) error("FAIL floats[2]")
	println("OK: floats[2] = ${floats[2]}")

	// ── Multi-statement lambda body ───────────────────────────────────
	val doubles = Array<Double>(3) {
		val temp = it.toDouble() * 2.5
		temp * temp
	}
	if (doubles[0] != 0.0) error("FAIL doubles[0]")
	println("OK: doubles[0] = ${doubles[0]}")
	if (doubles[1] != 6.25) error("FAIL doubles[1]")
	println("OK: doubles[1] = ${doubles[1]}")
	if (doubles[2] != 25.0) error("FAIL doubles[2]")
	println("OK: doubles[2] = ${doubles[2]}")

	// ── Multi-statement with compound assignment ──────────────────────
	val seq = Array<Int>(3) {
		var v = it * 10
		v += it
		v
	}
	if (seq[0] != 0) error("FAIL seq[0]")
	println("OK: seq[0] = ${seq[0]}")
	if (seq[1] != 11) error("FAIL seq[1]")
	println("OK: seq[1] = ${seq[1]}")
	if (seq[2] != 22) error("FAIL seq[2]")
	println("OK: seq[2] = ${seq[2]}")

	println("ALL OK")
	return 0
}
