// A local that names something read from a field, an array or a top-level property must keep
// it alive when that place is overwritten while the local is still in use - directly, by a
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

class Wrapper(private var mItem: Item) {
	private var mSwaps = 0

	fun current(): Item = mItem

	fun swap() {
		mSwaps++
		mItem = Item("swap " + mSwaps)
	}
}

class Outer(private val mWrapper: Wrapper) {
	fun inner(): Wrapper = mWrapper
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

	// What a function hands out - an element of a list, the value of a key, a field through a
	// getter - while the collection or the field changes under it.
	val vNames = arrayListOf(Item("n0"), Item("n1"), Item("n2"), Item("n3"))
	for (vIndex in vNames.indices) {
		val vName = vNames[vIndex]
		vNames[vIndex] = Item("new" + vIndex)
		print(vName.name + ">" + vNames[vIndex].name + " ")
	}
	println()
	val vMoved = ArrayList<Item>()
	for (vName in vNames) {
		vMoved.add(vName)
		if (vMoved.size == 2) vMoved.clear()
		print(vName.name + " ")
	}
	println(vMoved.size)
	val vLast = vNames.last()
	vNames.removeAt(vNames.size - 1)
	val vPicked = vNames.first { it.name.endsWith("1") }
	vNames.clear()
	println(vLast.name + " " + vPicked.name + " " + vNames.size)

	val vByName = HashMap<String, Item>()
	vByName["a"] = Item("value a")
	vByName["b"] = Item("value b")
	val vFound = vByName["a"]!!
	vByName["a"] = Item("other a")
	val vAgain = vByName.getValue("b")
	vByName.remove("b")
	println(vFound.name + " / " + vAgain.name + " / " + vByName["a"]!!.name + " / " + vByName.size)

	val vWrapped = Wrapper(Item("inside"))
	val vInside = vWrapped.current()
	vWrapped.swap()
	println(vInside.name + " then " + vWrapped.current().name)
	val vDeep = Outer(vWrapped).inner().current()
	vWrapped.swap()
	println(vDeep.name + " then " + vWrapped.current().name)

	// Read only: every element is seen once, whatever the list is.
	var vLength = 0
	for (vList2 in listOf(listOf(Item("p"), Item("qq")), arrayListOf(Item("rrr")), emptyList())) {
		for (vEach in vList2) vLength += vEach.name.length
	}
	println(vLength)

	// Nothing overwrites here: the same values either way.
	var vTotal = 0
	for (vRound in 0 until 100) {
		val vItem = vHolder.item
		vTotal += vItem.name.length + readOnly(vHolder)
	}
	println(vTotal)
}
