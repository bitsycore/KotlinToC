// A safe call whose block gives nothing (Unit) is Unit when the receiver is there: what follows `?:` is not reached.

fun check(inValue: Int) {
	if (inValue < 0) error("negative")
}

fun nothing(): Unit {}

fun main() {
	val vThere: Int? = 5
	val vMissing: Int? = null
	vThere?.let { check(it) } ?: println("not reached 1")
	vMissing?.let { check(it) } ?: println("reached 1")
	val vResult = vThere?.let { nothing() }
	println("${vResult == null} $vResult")
	val vNone = vMissing?.let { nothing() }
	println("${vNone == null} $vNone")
	vThere?.also { check(it) }?.let { println("also then let $it") } ?: println("not reached 2")
	vThere?.run { check(this) } ?: println("not reached 3")
	val vText: String? = "x"
	vText?.let { println("a block that prints") } ?: println("not reached 4")
	println((vThere?.let { nothing() } ?: "fallback").toString())
	println((vMissing?.let { nothing() } ?: "fallback").toString())
}
