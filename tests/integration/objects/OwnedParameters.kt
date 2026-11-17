// A function that stores a parameter can take the caller's reference to it over, when the
// caller is done with what it passes. These are the shapes that matter: the store reached,
// not reached (an exception first), the same object passed twice, the variable given a new
// value right after, and a caller that goes on using what it passed.

class Tag(val name: String)

class Cell(val value: Int, val next: Cell?)

class Pair2(val first: Tag, val second: Tag)

class Guarded(inLimit: Int, inTag: Tag) {
	val tag: Tag

	init {
		if (inLimit < 0) throw IllegalArgumentException("negative limit for ${inTag.name}")
		tag = inTag
	}
}

class Box {
	var tag: Tag? = null
	var count = 0

	fun put(inTag: Tag) {
		count++
		tag = inTag
	}

	fun putChecked(inTag: Tag, inAllowed: Boolean) {
		if (!inAllowed) throw IllegalStateException("not allowed: ${inTag.name}")
		tag = inTag
	}
}

val gRegistry = ArrayList<Tag>()
var gLatest: Tag? = null

fun register(inTag: Tag) {
	gLatest = inTag
}

fun chain(inCount: Int): Cell? {
	var vHead: Cell? = null
	for (vIndex in 0 until inCount) vHead = Cell(vIndex, vHead)
	return vHead
}

fun chainWithExit(inCount: Int): Cell? {
	var vHead: Cell? = null
	for (vIndex in 0 until inCount) {
		if (vIndex == 3) break
		vHead = Cell(vIndex, vHead)
	}
	return vHead
}

fun length(inHead: Cell?): Int {
	var vCount = 0
	var vCell = inHead
	while (vCell != null) {
		vCount++
		vCell = vCell.next
	}
	return vCount
}

fun main() {
	println(length(chain(5)))
	println(length(chainWithExit(10)))

	// The same object twice: one of the two is still needed after the first is taken.
	val vTag = Tag("twice")
	val vPair = Pair2(vTag, vTag)
	println("${vPair.first.name} ${vPair.second.name} ${vPair.first === vPair.second}")

	// The caller goes on using what it passed.
	val vKept = Tag("kept")
	val vBox = Box()
	vBox.put(vKept)
	println("${vKept.name} ${vBox.tag?.name} ${vBox.count}")

	// Passed at its last use, and replaced in the box right after.
	vBox.put(Tag("temporary"))
	vBox.put(Tag("replacement"))
	println("${vBox.tag?.name} ${vBox.count}")

	// The store is not reached.
	try {
		Guarded(-1, Tag("refused"))
	} catch (inError: IllegalArgumentException) {
		println(inError.message)
	}
	println(Guarded(1, Tag("accepted")).tag.name)
	try {
		vBox.putChecked(Tag("forbidden"), false)
	} catch (inError: IllegalStateException) {
		println(inError.message)
	}
	vBox.putChecked(Tag("allowed"), true)
	println(vBox.tag?.name)

	// Into a top-level property, and a variable assigned again after the call.
	var vCurrent = Tag("one")
	register(vCurrent)
	vCurrent = Tag("two")
	println("${gLatest?.name} ${vCurrent.name}")
	register(vCurrent)
	println("${gLatest?.name} ${vCurrent.name}")
}
