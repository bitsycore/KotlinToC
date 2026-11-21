/** Holds a waiter for reuse, as a dispatched continuation keeps its cancellable continuation. */
class Slot(val name: String) {
	var kept: Any? = null
}

/** Names the slot it is kept in, as the cancellable continuation names its delegate. */
class Waiter<T>(val slot: Slot, val value: T) {
	override fun toString(): String = "${slot.name}: $value"
}

/** Where the slots wait, so that they are objects of the heap: let go of at the end. */
var gSlots = ArrayList<Slot>()

/** A slot and a waiter that hold each other, kept in [gSlots] until it is cleared. */
fun <T> pair(inName: String, inValue: T): String {
	val vSlot = Slot(inName)
	val vWaiter = Waiter(vSlot, inValue)
	vSlot.kept = vWaiter
	gSlots.add(vSlot)
	return vWaiter.toString()
}

fun main() {
	// One instantiation of objects, and some of numbers and booleans: each a class of its own in C.
	println(pair("text", "a"))
	println(pair("int", 1))
	println(pair("boolean", true))
	println(pair("long", 2L))
	println(pair("nullable", null as Int?))
	// Nothing but each other holds the slots and the waiters now.
	gSlots = ArrayList()
	println(gSlots.size)
}
