// A member of a generic class read through a star projection (`other as W<*>`) of an object of
// another instantiation (W<Int> and W<String> are two classes in C): through the class of the
// object, never through the type. kotlinx-collections-immutable's ObjectWrapper found it.

class W<K : Comparable<K>>(val obj: K) {
	override fun equals(other: Any?): Boolean {
		if (other !is W<*>) return false
		return obj == other.obj
	}
	override fun hashCode() = obj.hashCode()
}

class Holder<V>(val value: V) {
	fun same(other: V) = value == other
}

fun main() {
	val a: Any = W(1)
	val b: Any = W("x")
	println(a == W(1))
	println(b == W("x"))
	println(a == b)
	println(Holder(W(1)).same(W(1)))
	println(Holder<Any>(W("y")).same(W(2)))
	val m = mutableMapOf<Any, Int>(W(1) to 1, W("s") to 2)
	println(m[W(1)].toString() + " " + m[W("s")])
}
