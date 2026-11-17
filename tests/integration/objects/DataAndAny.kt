package objects

data class Vec(val x: Int, val y: Int)

data class User(val name: String, val age: Int, val score: Double, val tag: Vec?, var active: Boolean = true)

class Plain(val id: Int)

fun show(value: Any?): String = when (value) {
	null -> "null"
	is Int -> "Int ${value + 1}"
	is Long -> "Long ${value * 2}"
	is Double -> "Double ${value / 2}"
	is Boolean -> if (value) "yes" else "no"
	is Char -> "Char ${value.code}"
	is String -> "String ${value.length}"
	is Vec -> "Vec ${value.x + value.y}"
	else -> "other"
}

fun same(a: Any?, b: Any?): Boolean = a == b

fun largest(values: Array<Any>): Int {
	var vBest = 0
	for (vValue in values) {
		if (vValue is Int && vValue > vBest) vBest = vValue
	}
	return vBest
}

fun main() {
	// Data classes: generated toString / equals / hashCode / copy / componentN
	val vA = Vec(1, 2)
	val vB = Vec(1, 2)
	println(vA)
	println(vA == vB)
	println(vA === vB)
	println(vA == Vec(2, 1))
	println(vA.hashCode() == vB.hashCode())
	println(vA.hashCode())
	val (vX, vY) = vA
	println(vX + vY)
	println(vA.copy(y = 9))

	val vUser = User("ann", 30, 4.5, vA)
	println(vUser)
	println(vUser.copy(name = "bob", tag = null))
	println(vUser == User("ann", 30, 4.5, Vec(1, 2)))
	println(vUser == User("ann", 30, 4.5, null))
	println(vUser.hashCode() == vUser.copy().hashCode())
	val (vName, vAge, vScore) = vUser
	println("$vName $vAge $vScore")
	vUser.active = false
	println(vUser.component5())
	println(User("z", 1, 0.0, null).hashCode())

	// Primitives used as Any are boxed
	println(show(41))
	println(show(21L))
	println(show(3.0))
	println(show(true))
	println(show('A'))
	println(show("four"))
	println(show(vA))
	println(show(null))
	println(show(Plain(1)))
	val vAny: Any = 7
	println(vAny)
	println(vAny.toString() + vAny.hashCode())
	println(vAny as Int + 1)
	println(vAny == 7)
	println(same(7, 7))
	println(same(7, 7L))
	println(same(2.5, 2.5))
	println(same("a", "a"))
	println(same(null, null))
	println(same(vA, vB))
	println(same(Plain(1), Plain(1)))
	val vBoxed: Any? = if (vX > 0) 2.5 else null
	println("boxed=$vBoxed")
	val vOptional: Int? = vY
	val vAsAny: Any? = vOptional
	println(vAsAny)
	val vMixed = arrayOf<Any>(3, "x", 12, 5L, 8)
	println(largest(vMixed))
	println(1.5.hashCode())
	println(true.hashCode())
	println(123456789012L.hashCode())
	println('c'.hashCode())
}
