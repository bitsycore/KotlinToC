// A function that returns what another function returns, which hands out what an object
// holds: the caller may read it without counting it only while the holder still holds it.

package returned

class Item(val name: String)

class Box(var item: Item) {
	fun get(): Item = item

	/** Through one more function. */
	fun again(): Item = get()
}

class Shelf {
	private val mItems = ArrayList<Item>()

	fun add(name: String) {
		mItems.add(Item(name))
	}

	fun at(index: Int): Item = mItems[index]

	fun first(): Item = at(0)

	fun removeFirst(): Item = mItems.removeAt(0)

	fun clear() {
		mItems.clear()
	}

	val size: Int get() = mItems.size
}

fun itemOf(box: Box): Item = box.get()

fun twice(box: Box): Item = itemOf(box)

fun pick(first: Box, second: Box, wantsFirst: Boolean): Item = if (wantsFirst) first.again() else itemOf(second)

/** The holder gives the object up while the caller still reads it. */
fun replaced(box: Box): String {
	val vOld = twice(box)
	box.item = Item("new")
	val vNew = box.again()
	return vOld.name + " then " + vNew.name
}

fun emptied(shelf: Shelf): String {
	val vFirst = shelf.first()
	val vText = StringBuilder(vFirst.name)
	shelf.clear()
	shelf.add("later")
	vText.append(" / ").append(vFirst.name).append(" / ").append(shelf.first().name)
	return vText.toString()
}

fun walked(shelf: Shelf): String {
	var vText = ""
	while (shelf.size > 0) {
		val vHead = shelf.first()
		val vTaken = shelf.removeFirst()
		vText += vHead.name + (if (vHead === vTaken) "=" else "!") + " "
	}
	return vText
}

fun main() {
	val vBox = Box(Item("old"))
	println(itemOf(vBox).name + " " + twice(vBox).name + " " + vBox.again().name)
	println(replaced(vBox))
	println(pick(vBox, Box(Item("other")), true).name + " " + pick(vBox, Box(Item("other")), false).name)

	val vShelf = Shelf()
	for (vName in listOf("a", "b", "c")) vShelf.add(vName)
	println(vShelf.first().name + vShelf.at(2).name)
	println(emptied(vShelf))
	for (vName in listOf("x", "y", "z")) vShelf.add(vName)
	println(walked(vShelf))

	var vKept = itemOf(vBox)
	for (vRound in 0 until 1000) {
		val vNow = twice(vBox)
		if (vRound % 100 == 0) vBox.item = Item("round $vRound")
		vKept = vNow
	}
	println(vKept.name + " " + vBox.get().name)
}
