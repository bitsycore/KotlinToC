// Sorting a list where it is: stable, by any comparator, and reported as a change.

class Card(val rank: Int, val label: String) {
	override fun toString(): String = "$rank$label"
}

fun scrambled(inCount: Int): ArrayList<Int> {
	val vList = ArrayList<Int>()
	var vSeed = 7
	for (vIndex in 0 until inCount) {
		vSeed = vSeed * 1103515245 + 12345
		vList += (vSeed ushr 16) % 50
	}
	return vList
}

fun isOrdered(inList: List<Int>): Boolean {
	for (vIndex in 1 until inList.size) if (inList[vIndex - 1] > inList[vIndex]) return false
	return true
}

fun main() {
	for (vCount in listOf(0, 1, 2, 3, 8, 9, 17, 100, 1000)) {
		val vList = scrambled(vCount)
		val vSum = vList.sum()
		vList.sort()
		println("$vCount: ordered ${isOrdered(vList)} sum kept ${vList.sum() == vSum} ${vList.take(6)}")
		vList.sortDescending()
		println("$vCount: down ${vList.take(6)}")
	}

	// Equal elements keep their order.
	val vCards = ArrayList<Card>()
	val vNumbers = scrambled(40)
	for (vIndex in 0 until 40) vCards += Card(vNumbers[vIndex] % 5, ('a' + vIndex % 26).toString() + vIndex)
	vCards.sortBy { it.rank }
	println(vCards)
	vCards.sortWith(compareByDescending<Card> { it.rank }.thenBy { it.label })
	println(vCards)
	vCards.sortWith { inLeft, inRight -> inLeft.label.length - inRight.label.length }
	println(vCards)

	val vWords = arrayListOf("pear", "fig", "apple", "kiwi", "date", "plum", "lime", "nut", "melon", "grape")
	vWords.sort()
	println(vWords)
	vWords.sortBy { it.length }
	println(vWords)
	val vDoubles = arrayListOf(2.5, -1.0, 0.0, -0.0, Double.NaN, 9.75, Double.NEGATIVE_INFINITY)
	vDoubles.sort()
	println(vDoubles)
	val vOptional = arrayListOf<Int?>(3, null, 1, null, 2)
	vOptional.sortWith(nullsLast(naturalOrder()))
	println(vOptional)
	println(vOptional.joinToString("|"))
	println("${vOptional.subList(0, 3) == listOf(1, 2, 3)} ${vOptional.subList(2, 5) == listOf(3, null, null)} ${vOptional.subList(2, 5) == listOf<Any?>(3, "x", null)} ${vOptional.subList(3, 5) == listOf<Any?>(null, 0)}")

	// A part of a list, which is not an ArrayList itself.
	val vPart = scrambled(12)
	vPart.subList(2, 9).sort()
	println(vPart)

	// What a part of a list does is done to the list.
	val vWhole = arrayListOf("a", "b", "c", "d", "e", "f", "g", "h")
	val vMiddle = vWhole.subList(2, 6)
	println("$vMiddle ${vMiddle.size} ${vMiddle.indexOf("e")} ${vMiddle.contains("g")} ${vMiddle[3]}")
	vMiddle[0] = "C"
	vMiddle += "x"
	vMiddle.add(1, "y")
	vMiddle.remove("e")
	vMiddle.removeAt(0)
	println("$vMiddle $vWhole")
	val vInner = vMiddle.subList(1, 3)
	vInner.clear()
	println("$vInner $vMiddle $vWhole")
	vWhole.subList(1, 4).clear()
	println("$vWhole ${vWhole.subList(0, 0)} ${vWhole.subList(3, 3).isEmpty()}")
	println(vWhole.subList(1, 3) == listOf("g", "h"))
	for (vText in vWhole.subList(0, 3)) print("$vText ")
	println()
	val vStale = vWhole.subList(0, 2)
	vWhole += "z"
	try {
		println(vStale.size)
	} catch (inError: ConcurrentModificationException) {
		println("the part is stale")
	}
	for ((vFrom, vTo) in listOf(-1 to 2, 2 to 99, 3 to 1)) {
		try {
			vWhole.subList(vFrom, vTo)
		} catch (inError: IndexOutOfBoundsException) {
			println("out of bounds: ${inError.message}")
		} catch (inError: IllegalArgumentException) {
			println("illegal: ${inError.message}")
		}
	}

	// A comparator that fails: the elements are all still there.
	val vFailing = scrambled(30)
	var vCalls = 0
	try {
		vFailing.sortWith { inLeft, inRight -> if (++vCalls == 20) throw IllegalStateException("stop") else inLeft - inRight }
	} catch (inError: IllegalStateException) {
		println("caught ${inError.message}, ${vFailing.size} elements")
	}

	// A comparator that changes the list.
	val vChanging = scrambled(30)
	try {
		vChanging.sortWith { inLeft, inRight ->
			if (vChanging.size == 30) vChanging += 99
			inLeft - inRight
		}
		println("not noticed")
	} catch (inError: ConcurrentModificationException) {
		println("noticed, ${vChanging.size} elements")
	}

	// Sorting is a change for a loop that is going over the list.
	val vLooped = scrambled(5)
	try {
		for (vValue in vLooped) vLooped.sort()
		println("not noticed")
	} catch (inError: ConcurrentModificationException) {
		println("noticed in the loop")
	}

	// Arrays, with the same sort.
	val vArray = Array(50) { Card(vNumbers[it % 40] % 4, "c$it") }
	vArray.sortBy { it.rank }
	println(vArray.joinToString(" "))
	val vSorted = arrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
	vSorted.sort()
	println(vSorted.joinToString())
}
