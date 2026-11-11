package objects

class Item(val name: String, var qty: Int) {
	override fun toString(): String = "$name x$qty"
}

fun sumOf(values: IntArray): Int {
	var vTotal = 0
	for (vValue in values) vTotal += vValue
	return vTotal
}

fun maxOf(vararg values: Int): Int {
	var vBest = values[0]
	for (vI in 1 until values.size) {
		if (values[vI] > vBest) vBest = values[vI]
	}
	return vBest
}

fun join(parts: Array<String>, separator: String = ", "): String {
	var vOut = ""
	for (vI in parts.indices) {
		if (vI > 0) vOut += separator
		vOut += parts[vI]
	}
	return vOut
}

fun squares(n: Int): IntArray = IntArray(n) { it * it }

fun sieve(limit: Int): Int {
	val vComposite = BooleanArray(limit + 1)
	var vCount = 0
	for (vI in 2..limit) {
		if (vComposite[vI]) continue
		vCount++
		var vJ = vI * 2
		while (vJ <= limit) {
			vComposite[vJ] = true
			vJ += vI
		}
	}
	return vCount
}

fun reverseInPlace(values: IntArray) {
	var vLow = 0
	var vHigh = values.size - 1
	while (vLow < vHigh) {
		val vTemp = values[vLow]
		values[vLow] = values[vHigh]
		values[vHigh] = vTemp
		vLow++
		vHigh--
	}
}

fun identity(size: Int): Array<IntArray> = Array(size) { vRow -> IntArray(size) { vColumn -> if (vRow == vColumn) 1 else 0 } }

fun total(inventory: Array<Item>): Int {
	var vSum = 0
	for (vItem in inventory) vSum += vItem.qty
	return vSum
}

fun firstAbove(values: IntArray, limit: Int): Int {
	val vFound = IntArray(1) { -1 }
	for (vI in values.indices) {
		if (values[vI] > limit) {
			vFound[0] = vI
			break
		}
	}
	return vFound[0]
}

fun main() {
	val vNumbers = intArrayOf(5, 3, 9, 1)
	println(vNumbers.size)
	println(vNumbers[2])
	println(sumOf(vNumbers))
	vNumbers[0] = 50
	vNumbers[3] += 6
	println(sumOf(vNumbers))
	reverseInPlace(vNumbers)
	println("${vNumbers[0]} ${vNumbers[1]} ${vNumbers[2]} ${vNumbers[3]}")
	println(maxOf(4, 8, 2))
	println(maxOf(7))
	println(firstAbove(vNumbers, 8))

	val vSquares = squares(6)
	println(sumOf(vSquares))
	println(squares(0).size)
	println(sieve(100))

	// Other primitive arrays
	val vDoubles = DoubleArray(3)
	vDoubles[1] = 2.5
	println(vDoubles[0] + vDoubles[1])
	val vLongs = longArrayOf(1L, 2L, 3000000000L)
	println(vLongs[2] * 2)
	val vChars = charArrayOf('k', 't', 'c')
	for (vChar in vChars) print(vChar)
	println()
	val vBytes = ByteArray(4) { (it * 100).toByte() }
	println("${vBytes[1]} ${vBytes[2]} ${vBytes[3]}")
	val vFlags = booleanArrayOf(true, false)
	println(vFlags[0] && !vFlags[1])

	// Arrays of references
	val vWords = arrayOf("alpha", "beta", "gamma")
	println(join(vWords))
	println(join(vWords, "-"))
	vWords[1] = vWords[0] + vWords[2]
	println(vWords[1].length)
	println(join(arrayOf()))
	val vInventory = Array(3) { Item("item$it", it + 1) }
	vInventory[1].qty += 10
	println(total(vInventory))
	println(vInventory[1])
	vInventory[0] = vInventory[2]
	println("${vInventory[0]} ${vInventory[0] === vInventory[2]}")
	val vSlots = arrayOfNulls<Item>(2)
	println(vSlots[0])
	vSlots[1] = Item("late", 1)
	println(vSlots[1]?.name)
	for (vSlot in vSlots) println(vSlot?.qty ?: 0)

	// Arrays of arrays
	val vMatrix = identity(3)
	var vTrace = 0
	for (vI in vMatrix.indices) vTrace += vMatrix[vI][vI]
	println(vTrace)
	vMatrix[0][2] = 7
	println(vMatrix[0][2] + vMatrix[2][0])
	for (vRow in vMatrix) {
		for (vCell in vRow) print(vCell)
		println()
	}

	// Nullable primitives and aliasing
	val vOptional = arrayOfNulls<Int>(2)
	vOptional[1] = 4
	println("${vOptional[0]} ${vOptional[1]}")
	val vAlias = vNumbers
	vAlias[0] = -1
	println(vNumbers[0])
	println(vAlias === vNumbers)
}
