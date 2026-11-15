// The cycle lint counts an object that is handed its owner's `this` and kept by it as a cycle,
// though both references are `val`s: an inner object, a lambda that calls a member, an object
// expression (SelfCycles.warnings). Such an object is never freed; the program makes none, so that
// the leak check has nothing to report.

class Outer {
	private val key = Key()
	val handler = { update() }
	val listener: Listener = object : Listener {
		override fun changed() {
			update()
		}
	}

	fun update() {
		println("update")
	}

	inner class Key {
		fun owner() = this@Outer
	}

	fun keyOwner() = key.owner()
}

fun interface Listener {
	fun changed()
}

/** No cycle: the child is handed something else than its owner. */
class Parent {
	val child = Child(Other())
}

class Child(val other: Other)

class Other

fun main() {
	println(Parent().child.other is Other)
	if ("make".length > 10) {
		val vOuter = Outer()
		vOuter.handler()
		vOuter.listener.changed()
		println(vOuter.keyOwner() === vOuter)
	}
}
