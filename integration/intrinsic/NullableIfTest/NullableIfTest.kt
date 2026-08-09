package NullableIfTest

// Regression test for D2: a value-typed `if`-expression with a `null` branch must lower each
// branch into the Optional (value -> KTC_SOME, null -> KTC_NONE). Before the fix the whole ternary
// was wrapped once, so the else-null branch produced SOME(0) instead of NONE - `x ?: d` returned 0
// instead of the default. The inline-extension form did not even compile.
// main returns a non-zero exit code if any check fails.

// Non-inline function, value-nullable via if/else.
fun digitOrNull(c: Char): Int? = if (c >= '0' && c <= '9') c.toInt() - '0'.toInt() else null

// Reversed branch order: the `null` is the THEN branch.
fun notDigitOrZero(c: Char): Int? = if (c < '0' || c > '9') null else c.toInt() - '0'.toInt()

// String? value-nullable if-expression.
fun labelOrNull(b: Boolean): String? = if (b) "yes" else null

// Complex (multi-statement) then-block hoisted to a temp, with a null else.
fun doubledDigitOrNull(c: Char): Int? =
	if (c >= '0' && c <= '9') {
		val v = c.toInt() - '0'.toInt()
		v * 2
	} else null

// Inline extension on a primitive receiver - the original D2 case.
inline fun Char.toDigitOrNull(): Int? = if (this >= '0' && this <= '9') this.toInt() - '0'.toInt() else null

// when-expression, value-nullable via a `null` else (nested-ternary form). (D4)
fun gradeOrNull(score: Int): Char? = when {
	score >= 90 -> 'A'
	score >= 80 -> 'B'
	else -> null
}

// when-expression, value-nullable, with a multi-statement value branch (temp-hoist form). (D4)
fun absDigitOrNull(c: Char): Int? = when {
	c >= '0' && c <= '9' -> {
		val d = c.toInt() - '0'.toInt()
		d
	}
	else -> null
}

fun main(): Int {
	var vOk = true

	// Non-inline: digit present -> value; absent -> null (default applies).
	vOk = vOk && (digitOrNull('5') ?: -1) == 5
	vOk = vOk && (digitOrNull('x') ?: -1) == -1      // was 0 before the fix

	// Reversed branches.
	vOk = vOk && (notDigitOrZero('3') ?: -1) == 3
	vOk = vOk && (notDigitOrZero('!') ?: -7) == -7   // null then-branch -> default

	// String? form.
	vOk = vOk && (labelOrNull(true) ?: "no") == "yes"
	vOk = vOk && (labelOrNull(false) ?: "no") == "no"

	// Complex-block (temp-hoist) form.
	vOk = vOk && (doubledDigitOrNull('4') ?: -1) == 8
	vOk = vOk && (doubledDigitOrNull(' ') ?: -1) == -1

	// Inline extension on a primitive (literal and variable receiver).
	vOk = vOk && ('9'.toDigitOrNull() ?: -1) == 9
	val vc = 'z'
	vOk = vOk && (vc.toDigitOrNull() ?: -1) == -1

	// when-expression value-nullable forms (D4): value branches and the null else.
	vOk = vOk && (gradeOrNull(95) ?: ' ') == 'A'
	vOk = vOk && (gradeOrNull(85) ?: ' ') == 'B'
	vOk = vOk && (gradeOrNull(50) ?: '?') == '?'   // null else -> default
	vOk = vOk && (absDigitOrNull('6') ?: -1) == 6
	vOk = vOk && (absDigitOrNull('q') ?: -1) == -1 // null else -> default (was SOME(0) before)

	if (!vOk) {
		println("NullableIfTest FAILED")
		return 1
	}
	println("NullableIfTest OK")
	return 0
}
