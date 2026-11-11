package stdlib

data class Player(val name: String, val score: Int)

fun main() {
	val vNumbers = intArrayOf(5, 3, 9, 1, 7)
	val vWords = arrayOf("pear", "apple", "fig", "banana")

	// Reading
	println(vNumbers.joinToString())
	println(vNumbers.sum())
	println(vNumbers.average())
	println("${vNumbers.min()} ${vNumbers.max()} ${vNumbers.first()} ${vNumbers.last()}")
	println(vNumbers.contains(9))
	println(vNumbers.indexOf(1))
	println(vNumbers.contentToString())
	println(vWords.contentToString())
	println(vNumbers.toList())
	println(vWords.joinToString("|") { it.uppercase() })
	println(vNumbers.isEmpty())
	println(IntArray(0).isEmpty())
	println(vNumbers.indices)
	println(vNumbers.lastIndex)

	// Transforming
	println(vNumbers.map { it * 2 })
	println(vNumbers.filter { it > 4 })
	println(vWords.map { it.length })
	println(vWords.filter { it.length > 3 }.size)
	println(vNumbers.fold(0) { vTotal, vValue -> vTotal + vValue })
	println(vNumbers.any { it > 8 })
	println(vNumbers.all { it > 0 })
	println(vNumbers.count { it % 2 == 1 })
	println(vWords.associateWith { it.length })
	println(vNumbers.reversed())
	println(vNumbers.take(2))
	println(vNumbers.drop(3))
	println(vWords.firstOrNull { it.startsWith("b") })
	println(vNumbers.sumOf { it.toLong() * it })

	// Sorting
	println(vNumbers.sorted())
	println(vNumbers.sortedDescending())
	println(vWords.sorted())
	println(vWords.sortedBy { it.length })
	val vSorted = vNumbers.copyOf()
	vSorted.sort()
	println(vSorted.contentToString())
	println(vNumbers.contentToString())
	val vPart = intArrayOf(9, 8, 7, 6, 5, 4)
	vPart.sort(1, 5)
	println(vPart.contentToString())
	val vDoubles = doubleArrayOf(2.5, -1.0, 0.0, 10.25)
	vDoubles.sort()
	println(vDoubles.contentToString())
	val vChars = charArrayOf('d', 'a', 'c', 'b')
	vChars.sort()
	println(vChars.concatToString())
	val vLongs = LongArray(200) { (it * 7919L) % 101 }
	vLongs.sort()
	println("${vLongs[0]} ${vLongs[100]} ${vLongs[199]}")
	val vPlayers = arrayOf(Player("ann", 3), Player("bob", 1), Player("cy", 3), Player("di", 2))
	vPlayers.sortWith(compareBy { it.score })
	println(vPlayers.map { it.name })
	vWords.sort()
	println(vWords.contentToString())
	vWords.sortDescending()
	println(vWords.contentToString())

	// Copying and filling
	println(vNumbers.copyOf(3).contentToString())
	println(vNumbers.copyOf(7).contentToString())
	println(vNumbers.copyOfRange(1, 4).contentToString())
	println(vWords.copyOf(5).contentToString())
	val vFilled = IntArray(5)
	vFilled.fill(4)
	vFilled.fill(9, 1, 3)
	println(vFilled.contentToString())
	val vTarget = IntArray(6)
	vNumbers.copyInto(vTarget, 1, 0, 3)
	println(vTarget.contentToString())
	vTarget.copyInto(vTarget, 2, 0, 4)
	println(vTarget.contentToString())
	println((vNumbers + 100).contentToString())
	println((vNumbers + intArrayOf(1, 2)).contentToString())
	println((vWords + "kiwi").size)
	println((vNumbers + listOf(4, 5)).size)

	// Comparing
	println(intArrayOf(1, 2).contentEquals(intArrayOf(1, 2)))
	println(intArrayOf(1, 2).contentEquals(intArrayOf(2, 1)))
	println(intArrayOf(1, 2).contentHashCode() == intArrayOf(1, 2).contentHashCode())
	println(arrayOf("a", "b").contentEquals(arrayOf("a", "b")))
	val vNested = arrayOf(intArrayOf(1, 2), intArrayOf(3))
	println(vNested.contentDeepToString())
	println(vNested.contentDeepEquals(arrayOf(intArrayOf(1, 2), intArrayOf(3))))
	println(arrayOf(arrayOf("x"), arrayOf("y", "z")).contentDeepToString())

	// Views and conversions
	val vView = vNumbers.asList()
	vNumbers[0] = 50
	println(vView[0])
	println(vView.size)
	println(vView.subList(1, 3))
	println(vNumbers.toTypedArray().size)
	println(vWords.toList().reversed())
	println(vNumbers.toSet().size)
	println(vNumbers.toMutableList().also { it.add(0) })
	println(byteArrayOf(1, 2, 3).sum())
	println(booleanArrayOf(true, false).contentToString())
	println(floatArrayOf(1.5f, 2.5f).sum())

	// Failures
	try {
		vNumbers.copyOfRange(3, 9)
	} catch (vError: IndexOutOfBoundsException) {
		println("range")
	}
	try {
		IntArray(0).first()
	} catch (vError: NoSuchElementException) {
		println(vError.message)
	}
	println("done")
}
