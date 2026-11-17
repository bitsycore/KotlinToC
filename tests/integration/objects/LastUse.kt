// A reference handed over at its last use, and a local that borrows only up to its last
// use: what is read afterwards, or overwritten in between, must still be right.

class Part(val name: String) {
	override fun toString(): String = "Part($name)"
}

class Slot(var part: Part) {
	var spare: Part? = null

	/** Overwrites the place its argument was read from, then uses the argument. */
	fun replaceThenUse(inOld: Part): Part {
		part = Part("fresh")
		return Part(inOld.name + " was here")
	}

	fun combine(inOther: Part): Part = Part(part.name + "+" + inOther.name)

	fun rename(inSuffix: String) {
		part = Part(part.name + inSuffix)
	}
}

class Tree(val label: String) {
	val children = ArrayList<Tree>()
	var parent: Tree? = null

	fun add(inChild: Tree): Tree {
		children.add(inChild)
		return this
	}
}

class Registry {
	var last: Part? = null
	val all = ArrayList<Part>()

	fun register(inName: String): Part {
		val vPart = Part(inName)
		all.add(vPart)
		last = vPart
		return vPart
	}
}

var gKept: Part? = null

fun keep(inPart: Part) {
	gKept = inPart
}

fun build(inFlag: Boolean): Part {
	val vPart = Part("built")
	keep(vPart)
	if (inFlag) {
		gKept = null
		return vPart
	}
	val vAgain = vPart
	gKept = null
	return vAgain
}

fun main() {
	// x.f = g(x.f): the old value is only borrowed until the call returns.
	val vSlot = Slot(Part("a"))
	for (vRound in 0 until 3) vSlot.part = vSlot.combine(vSlot.part)
	println(vSlot.part)
	// The callee overwrites the field while it still uses what was read from it.
	vSlot.part = vSlot.replaceThenUse(vSlot.part)
	println(vSlot.part)
	// The value read is used after the field changed.
	val vBefore = vSlot.part
	vSlot.rename("!")
	println("$vBefore then ${vSlot.part}")
	val vOld = vSlot.part
	vSlot.part = Part("new")
	vSlot.spare = vOld
	println("${vSlot.spare} ${vSlot.part} $vOld")

	// Made, set up, stored: the last use hands the object to the place.
	val vRegistry = Registry()
	val vFirst = vRegistry.register("first")
	vRegistry.register("second")
	println("$vFirst ${vRegistry.last} ${vRegistry.all.size}")
	var vHead: Tree? = null
	for (vI in 0 until 3) {
		val vTree = Tree("t$vI")
		vTree.parent = vHead
		vHead = vTree
	}
	println(vHead?.label + vHead?.parent?.label + vHead?.parent?.parent?.label)

	// Handed over in one branch only, and assigned again afterwards.
	var vMaybe: Tree? = Tree("root").add(Tree("leaf"))
	val vLeaf = vMaybe!!.children[0]
	vMaybe = null
	println(vLeaf.label)
	var vKept: Part? = Part("kept")
	val vHolder = Slot(Part("held"))
	if (vHolder.part.name.length > 2) {
		vHolder.part = vKept!!
	} else {
		println(vKept)
	}
	vKept = null
	println("${vHolder.part} $vKept")
	val vTwice = Part("twice")
	val vSlots = listOf(Slot(vTwice), Slot(vTwice))
	println(vSlots[0].part === vSlots[1].part)

	// Something borrows from a local before the local is stored somewhere that is then cleared.
	val vTree = Tree("outer").add(Tree("inner"))
	val vInner = vTree.children[0]
	val vTrees = ArrayList<Tree>()
	vTrees.add(vTree)
	vTrees.clear()
	println(vInner.label + " of " + vTree.label)
	val vPart = Part("lent")
	val vName = vPart.name
	gKept = vPart
	gKept = null
	println(vName)

	println(build(true).name + build(false).name)
	// In a loop the same statement runs again: nothing may be given away twice.
	val vShared = Part("shared")
	val vList = ArrayList<Part>()
	for (vI in 0 until 3) vList.add(vShared)
	var vLast: Part? = null
	for (vI in 0 until 3) vLast = vShared
	println("${vList.size} $vLast $vShared")
}
