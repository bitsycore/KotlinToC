package ValueClassTest

value class UserId(val raw: Int)
value class Meters(val value: Float)
value class Token(val raw: String)

fun showId(id: UserId) {
	println("id=$id")
}

fun showDist(d: Meters) {
	println("dist=$d")
}

fun showToken(t: Token) {
	println("token=$t")
}

fun addIds(a: UserId, b: UserId): UserId {
	return UserId(a.raw + b.raw)
}

fun main() {
	// Basic construction and property access
	val id = UserId(42)
	if (id.raw != 42) error("FAIL UserId raw access")
	println("UserId raw access: ok")

	// Value class as parameter
	showId(id)
	showDist(Meters(3.14f))
	showToken(Token("abc123"))

	// Arithmetic through unwrapped value
	val sum = addIds(UserId(10), UserId(32))
	if (sum.raw != 42) error("FAIL addIds")
	println("addIds: ok")

	// Equality
	val a = UserId(7)
	val b = UserId(7)
	val c = UserId(8)
	if (a.raw != b.raw) error("FAIL UserId equality")
	if (a.raw == c.raw) error("FAIL UserId inequality")
	println("UserId equality: ok")

	// Float value class
	val m1 = Meters(1.5f)
	val m2 = Meters(2.5f)
	val mSum = Meters(m1.value + m2.value)
	if (mSum.value != 4.0f) error("FAIL Meters sum")
	println("Meters sum: ok")

	// String value class
	val tok = Token("hello")
	if (tok.raw != "hello") error("FAIL Token raw access")
	println("Token raw access: ok")

	println("ALL OK")
}
