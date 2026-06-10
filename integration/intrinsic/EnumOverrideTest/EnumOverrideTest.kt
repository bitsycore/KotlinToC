package EnumOverrideTest

// Phase 3: per-entry method overrides on a full Kotlin-style enum.
// Each entry supplies its own `eval` body; the call site dispatches through
// a per-entry vtable. The `describe` method has no overrides — calls go
// straight to the default impl, no vtable indirection.
enum class Op(val sym: String) {
	PLUS("+")  { override fun eval(a: Int, b: Int): Int = a + b },
	MINUS("-") { override fun eval(a: Int, b: Int): Int = a - b },
	TIMES("*") { override fun eval(a: Int, b: Int): Int = a * b },
	DIV("/")   { override fun eval(a: Int, b: Int): Int = a / b };

	// Default impl — replaced by the per-entry overrides above.
	fun eval(a: Int, b: Int): Int = 0
	// Non-virtual method — every entry uses this directly.
	fun describe(): String = sym
}

fun testEntryFields() {
	if (Op.PLUS.sym  != "+") fatalError("FAIL Op.PLUS.sym")
	if (Op.MINUS.sym != "-") fatalError("FAIL Op.MINUS.sym")
	if (Op.PLUS.ordinal != 0) fatalError("FAIL Op.PLUS.ordinal")
	if (Op.DIV.ordinal  != 3) fatalError("FAIL Op.DIV.ordinal")
}

fun testOverrideDispatch() {
	if (Op.PLUS.eval(2, 3)  != 5)  fatalError("FAIL Op.PLUS.eval")
	if (Op.MINUS.eval(7, 4) != 3)  fatalError("FAIL Op.MINUS.eval")
	if (Op.TIMES.eval(6, 7) != 42) fatalError("FAIL Op.TIMES.eval")
	if (Op.DIV.eval(20, 4)  != 5)  fatalError("FAIL Op.DIV.eval")
}

fun testDispatchAcrossValues() {
	// Iterate through .values() — virtual dispatch must pick the per-entry impl.
	val all = Op.values()
	var sum = 0
	for (i in 0 until all.size) {
		sum += all[i].eval(10, 2)
	}
	// 10+2 + 10-2 + 10*2 + 10/2 = 12 + 8 + 20 + 5 = 45
	if (sum != 45) fatalError("FAIL sum=$sum")
}

fun testNonVirtualMethod() {
	// describe() has no entry override → direct call, not through vtable.
	if (Op.PLUS.describe()  != "+") fatalError("FAIL describe PLUS")
	if (Op.TIMES.describe() != "*") fatalError("FAIL describe TIMES")
}

fun testValueOfThenDispatch() {
	val op = Op.valueOf("MINUS")
	if (op.eval(9, 4) != 5) fatalError("FAIL valueOf+eval")
}

fun main(args: Array<String>) {
	testEntryFields()
	testOverrideDispatch()
	testDispatchAcrossValues()
	testNonVirtualMethod()
	testValueOfThenDispatch()
	println("ALL OK")
}
