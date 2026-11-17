// A lambda given to an inline function whose parameter is the Unit a call returned:
// `validate(x).let { ... }`, as kotlinx-datetime writes it.

fun validate(inValue: Int) {
	if (inValue < 0) throw IllegalArgumentException("negative: $inValue")
}

fun describe(inValue: Int): String = validate(inValue).let { "valid $inValue" }

fun same(inValue: Int): Any = validate(inValue).let { it }

fun main() {
	println(describe(3))
	println(same(4))
	println(Unit.let { it.toString() })
	validate(5).also { println("also $it") }
	println(deep())
	try {
		describe(-1)
	} catch (vError: IllegalArgumentException) {
		println("caught ${vError.message}")
	}
}

val depth = DeepRecursiveFunction<Unit, Int> { 3 }

fun deep(): Int = depth(Unit)
