package EnumFullTest

// Full Kotlin-style enum: primary ctor params, per-entry args, body method.
enum class Op(val sym: String) {
	PLUS("+"), MINUS("-"), TIMES("*"), DIV("/");
	fun eval(a: Int, b: Int): Int = when (this) {
		PLUS  -> a + b
		MINUS -> a - b
		TIMES -> a * b
		DIV   -> a / b
	}
}

fun testEntryFields() {
	val p = Op.PLUS
	println(p.sym)
	println(p.name)
	println(p.ordinal)
	if (p.sym     != "+")    error("FAIL Op.PLUS.sym")
	if (p.name    != "PLUS") error("FAIL Op.PLUS.name")
	if (p.ordinal != 0)      error("FAIL Op.PLUS.ordinal")

	val t = Op.TIMES
	if (t.sym     != "*")     error("FAIL Op.TIMES.sym")
	if (t.name    != "TIMES") error("FAIL Op.TIMES.name")
	if (t.ordinal != 2)       error("FAIL Op.TIMES.ordinal")
}

fun testEquality() {
	val a = Op.PLUS
	val b = Op.PLUS
	val c = Op.MINUS
	if (!(a == b)) error("FAIL: Op.PLUS == Op.PLUS")
	if ( (a == c)) error("FAIL: Op.PLUS != Op.MINUS")
	if (!(a != c)) error("FAIL: Op.PLUS != Op.MINUS (op !=)")
}

fun testWhen() {
	val all = Op.values()
	var sum = 0
	for (i in 0 until all.size) {
		val op = all[i]
		val tag = when (op) {
			Op.PLUS  -> 1
			Op.MINUS -> 2
			Op.TIMES -> 4
			Op.DIV   -> 8
		}
		sum += tag
	}
	if (sum != 15) error("FAIL testWhen sum=$sum")
}

fun testMethod() {
	if (Op.PLUS.eval(2, 3)  != 5)  error("FAIL Op.PLUS.eval")
	if (Op.MINUS.eval(7, 4) != 3)  error("FAIL Op.MINUS.eval")
	if (Op.TIMES.eval(6, 7) != 42) error("FAIL Op.TIMES.eval")
	if (Op.DIV.eval(20, 4)  != 5)  error("FAIL Op.DIV.eval")
}

fun testValuesAndValueOf() {
	val all = Op.values()
	if (all.size != 4) error("FAIL values size")
	if (all[0].name != "PLUS")  error("FAIL values[0]")
	if (all[3].name != "DIV")   error("FAIL values[3]")
	val pl = Op.valueOf("PLUS")
	if (pl != Op.PLUS) error("FAIL valueOf PLUS")
	val tm = Op.valueOf("TIMES")
	if (tm.ordinal != 2) error("FAIL valueOf TIMES ordinal")
}

fun testPrintAndTemplate() {
	val op = Op.MINUS
	// println(op) should print the entry name.
	println(op)
	// Inside a template: "$op" should also produce the entry name.
	val s = "op=$op"
	if (s != "op=MINUS") error("FAIL template, got $s")
}

fun main(args: Array<String>) {
	testEntryFields()
	testEquality()
	testWhen()
	testMethod()
	testValuesAndValueOf()
	testPrintAndTemplate()
	println("ALL OK")
}
