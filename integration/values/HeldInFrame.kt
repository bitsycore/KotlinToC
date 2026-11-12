// An object stored into a field of another one that stays in the frame (a closure taking what
// its lambda uses, a holder that is a local) can stay there too - unless a read of that field
// lets it out. Each case here prints what the JVM prints; the point is that none reads freed
// memory, whichever the analysis decided.

class Item(val name: String) {
	var count = 0
}

class Holder {
	var item: Item? = null
	var other: Item? = null
}

val gKept = ArrayList<Item>()
var gLast: Item? = null

fun twice(inBlock: () -> Unit) {
	inBlock()
	inBlock()
}

fun itemOf(inHolder: Holder): Item? = inHolder.item

fun keep(inHolder: Holder) {
	gLast = inHolder.other
}

// ==================
// MARK: Stays in the frame
// ==================

fun captured(): Int {
	val vItem = Item("captured")
	var vTotal = 0
	twice {
		vItem.count++
		vTotal += vItem.count
	}
	return vTotal + vItem.count
}

fun held(): String {
	val vHolder = Holder()
	val vItem = Item("held")
	vHolder.item = vItem
	vItem.count = 5
	return "${vHolder.item?.name} ${vHolder.item?.count}"
}

// ==================
// MARK: Gets out
// ==================

fun leakedByLambda() {
	val vItem = Item("leaked by a lambda")
	twice { gKept += vItem }
}

fun readBack(): Item? {
	var vOuter: Item? = null
	if (gKept.size >= 0) {
		val vItem = Item("read back")
		val vHolder = Holder()
		vHolder.item = vItem
		vOuter = vHolder.item
	}
	return vOuter
}

fun readBackLocally(): String {
	var vOuter: Item? = null
	for (vRound in 0 until 3) {
		val vItem = Item("round $vRound")
		val vHolder = Holder()
		vHolder.item = vItem
		if (vRound == 1) vOuter = vHolder.item
	}
	return vOuter?.name ?: "none"
}

fun returnedHolder(): Holder {
	val vHolder = Holder()
	vHolder.item = Item("in a returned holder")
	return vHolder
}

fun throughCallee(): Item? {
	val vHolder = Holder()
	vHolder.item = Item("through a callee")
	return itemOf(vHolder)
}

fun keptByCallee() {
	val vHolder = Holder()
	vHolder.other = Item("kept by a callee")
	keep(vHolder)
}

fun outerHolder(): String {
	val vHolder = Holder()
	for (vRound in 0 until 3) {
		val vItem = Item("outer $vRound")
		vHolder.item = vItem
	}
	return vHolder.item?.name ?: "none"
}

fun aliasOutside(): String {
	val vOuter = Holder()
	if (gKept.size >= 0) {
		val vItem = Item("alias outside")
		val vAlias = vOuter
		vAlias.item = vItem
	}
	return vOuter.item?.name ?: "none"
}

fun fromOneToOther(): String {
	val vLong = Holder()
	if (gKept.size >= 0) {
		val vShort = Holder()
		vShort.item = Item("from one holder to another")
		vLong.other = vShort.item
	}
	return vLong.other?.name ?: "none"
}

// ==================
// MARK: Kept by a constructor
// ==================

class Keeper(val item: Item) {
	val items = ArrayList<Item>()
	var spare: Item? = null

	fun take(inItem: Item) {
		spare = inItem
	}
}

class Outer(val keeper: Keeper, val label: String)

class Announcer(val item: Item) {
	init {
		gLast = item
	}
}

fun built(): String {
	val vKeeper = Keeper(Item("built"))
	vKeeper.take(Item("spare"))
	vKeeper.items += Item("listed")
	return "${vKeeper.item.name} ${vKeeper.spare?.name} ${vKeeper.items.size}"
}

fun nested(): String {
	val vOuter = Outer(Keeper(Item("nested")), "outer")
	return "${vOuter.label} ${vOuter.keeper.item.name}"
}

fun builtAndReturned(): Keeper = Keeper(Item("built and returned"))

fun itemOfKeeper(): Item {
	val vKeeper = Keeper(Item("out of its keeper"))
	return vKeeper.item
}

fun spareOfKeeper(): Item? {
	val vKeeper = Keeper(Item("first"))
	vKeeper.take(Item("spare out of its keeper"))
	return vKeeper.spare
}

fun announced(): String {
	val vAnnouncer = Announcer(Item("announced"))
	return vAnnouncer.item.name
}

fun keepers(): Int {
	var vTotal = 0
	for (vRound in 0 until 4) {
		val vKeeper = Keeper(Item("round $vRound"))
		vTotal += vKeeper.item.name.length
	}
	return vTotal
}

fun lambdaKept(): () -> String {
	val vItem = Item("in a lambda that is returned")
	return { vItem.name }
}

fun main() {
	println(captured())
	println(held())
	leakedByLambda()
	println(gKept.map { it.name })
	println(readBack()?.name)
	println(readBackLocally())
	println(returnedHolder().item?.name)
	println(throughCallee()?.name)
	keptByCallee()
	println(gLast?.name)
	println(outerHolder())
	println(aliasOutside())
	println(fromOneToOther())
	println(lambdaKept()())
	println(built())
	println(nested())
	println(builtAndReturned().item.name)
	println(itemOfKeeper().name)
	println(spareOfKeeper()?.name)
	println("${announced()} ${gLast?.name}")
	println(keepers())
}
