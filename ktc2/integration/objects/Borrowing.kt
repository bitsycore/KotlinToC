// A local that names something read from a field, an array or a top-level property must keep
// it alive when that place is overwritten while the local is still in use — directly, by a
// function called, by a lambda, or by the toString a print calls.

class Item(val name: String) {
	override fun toString(): String = "Item($name)"
}

class Holder(var item: Item) {
	fun replace(inName: String) {
		item = Item(inName)
	}
}

class Chain(var next: Chain?, val label: String) {
	fun cut(): String {
		val vNext = next
		next = null
		return vNext?.label ?: "none"
	}
}

class Loud(val holder: Holder) {
	override fun toString(): String {
		holder.item = Item("changed by toString")
		return "Loud"
	}
}

var gCurrent = Item("global one")

fun swapGlobal() {
	gCurrent = Item("global two")
}

fun useBoth(inFirst: Item, inHolder: Holder): String {
	inHolder.replace("replaced in callee")
	return inFirst.name + " / " + inHolder.item.name
}

fun readOnly(inHolder: Holder): Int = inHolder.item.name.length

fun main() {
	// Overwritten directly.
	val vHolder = Holder(Item("first"))
	val vBefore = vHolder.item
	vHolder.item = Item("second")
	println(vBefore.name + " then " + vHolder.item.name)

	// Overwritten by a method.
	val vKept = vHolder.item
	vHolder.replace("third")
	println(vKept.name + " then " + vHolder.item.name)

	// Passed as an argument to a function that overwrites the field it was read from.
	println(useBoth(vHolder.item, vHolder))

	// Overwritten by a lambda.
	val vChange = { vHolder.item = Item("from lambda") }
	val vOld = vHolder.item
	vChange()
	println(vOld.name + " then " + vHolder.item.name)

	// Overwritten by the toString of something printed.
	val vSeen = vHolder.item
	println("${Loud(vHolder)} saw ${vSeen.name}")
	println("now " + vHolder.item.name)

	// An array element replaced, in place and through a function.
	val vItems = arrayOf(Item("a"), Item("b"), Item("c"))
	val vFirst = vItems[0]
	vItems[0] = Item("z")
	val vSecond = vItems[1]
	vItems.fill(Item("filled"))
	println(vFirst.name + vSecond.name + vItems[0].name + vItems[1].name)

	// A list that drops its elements.
	val vList = arrayListOf(Item("x"), Item("y"))
	val vHead = vList[0]
	vList.clear()
	println(vHead.name + " after clear " + vList.size)

	// A top-level property replaced by a function.
	val vGlobal = gCurrent
	swapGlobal()
	println(vGlobal.name + " then " + gCurrent.name)

	// A chain whose link is cut while it is being followed.
	val vChain = Chain(Chain(null, "tail"), "head")
	println(vChain.cut() + " " + vChain.cut())

	// Nothing overwrites here: the same values either way.
	var vTotal = 0
	for (vRound in 0 until 100) {
		val vItem = vHolder.item
		vTotal += vItem.name.length + readOnly(vHolder)
	}
	println(vTotal)
}
