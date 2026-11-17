/** A private property of a generic class read on another instance, which is of a class below it. */
abstract class Key<T>(base: Key<*>?) {
	private val topmost: Key<*> = if (base is Key<*>) base.topmost else this

	fun isUnder(other: Key<*>): Boolean = other === this || topmost === other
	fun root(): Key<*> = topmost
}

object Root : Key<String>(null)

object Child : Key<Int>(Root)

class Leaf(base: Key<*>) : Key<Long>(base)

fun main() {
	println(Child.isUnder(Root))
	println(Root.isUnder(Child))
	println(Leaf(Child).root() === Root)
	println(Leaf(Leaf(Root)).isUnder(Root))
}
