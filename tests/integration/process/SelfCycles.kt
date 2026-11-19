// Objects that hold each other through their own fields. Two of them (an inner object, a lambda that calls a
// member, an object expression, each kept by the object it was handed) are freed by the runtime when nothing else
// holds either: the leak check at the end has nothing to report. A ring of three is reported by the cycle lint
// (SelfCycles.warnings), and never formed here.

class Outer(val name: String) {
	private val key = Key()
	val handler = { update() }
	val listener: Listener = object : Listener {
		override fun changed() {
			update()
		}
	}

	fun update() {
		println("update $name")
	}

	inner class Key {
		fun owner() = this@Outer
	}

	fun keyOwner() = key.owner()

	fun leakKey(): Any = key
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

/** Three classes in a ring through `var`s: not freed if it forms, and reported. */
class First(var next: Second?)
class Second(var next: Third?)
class Third(var next: First?)

val gKept = ArrayList<Any>()

fun main() {
	println(Parent().child.other is Other)
	val vOuter = Outer("local")
	vOuter.handler()
	vOuter.listener.changed()
	println(vOuter.keyOwner() === vOuter)
	for (vIndex in 0 until 3) {
		val vMade = Outer("round $vIndex")
		vMade.handler()
	}
	// The inner object kept elsewhere: the pair is not freed while it is, and is after.
	val vHeld = Outer("held")
	gKept += vHeld.leakKey()
	gKept += vHeld.handler
	println(gKept.size)
	gKept.clear()
	// One of the pair kept by a local while the other is given up.
	var vOwner: Outer? = Outer("owner")
	val vHandler = vOwner!!.handler
	vOwner = null
	vHandler()
	println(vOwner == null)
	val vRing = First(Second(Third(null)))
	println(vRing.next?.next?.next == null)
}
