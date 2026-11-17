// Arrays whose size is known only when the program runs: small ones stay in the frame,
// large ones and the ones that leave their block go to the heap. The results are the same.

class Item(val id: Int) {
	override fun toString(): String = "Item($id)"
}

fun sumOfSquares(inCount: Int): Int {
	val vSquares = IntArray(inCount)
	for (vIndex in 0 until inCount) vSquares[vIndex] = vIndex * vIndex
	var vSum = 0
	for (vValue in vSquares) vSum += vValue
	return vSum
}

fun average(inCount: Int): Double {
	val vValues = DoubleArray(inCount) { it * 0.5 }
	return if (inCount == 0) 0.0 else vValues.sum() / inCount
}

fun describe(inCount: Int): String {
	val vItems = arrayOfNulls<Item>(inCount)
	for (vIndex in 0 until inCount) vItems[vIndex] = Item(vIndex)
	val vText = StringBuilder()
	for (vItem in vItems) vText.append(vItem).append(' ')
	return vText.toString().trim()
}

fun sieve(inLimit: Int): Int {
	val vComposite = BooleanArray(inLimit + 1)
	var vPrimes = 0
	for (vNumber in 2..inLimit) {
		if (vComposite[vNumber]) continue
		vPrimes++
		var vMultiple = vNumber * 2
		while (vMultiple <= inLimit) {
			vComposite[vMultiple] = true
			vMultiple += vNumber
		}
	}
	return vPrimes
}

fun kept(inCount: Int): IntArray {
	val vArray = IntArray(inCount)
	for (vIndex in 0 until inCount) vArray[vIndex] = vIndex + 1
	return vArray
}

fun main() {
	println(sumOfSquares(0))
	println(sumOfSquares(5))
	println(sumOfSquares(64))
	println(sumOfSquares(1000))
	println(average(4))
	println(average(300))
	println(describe(3))
	println(describe(40).length)
	println(sieve(100))
	println(sieve(10000))

	// The same block run many times: each round has its own array.
	var vTotal = 0L
	for (vRound in 1..2000) {
		val vRow = LongArray(vRound % 37)
		for (vIndex in vRow.indices) vRow[vIndex] = (vRound + vIndex).toLong()
		for (vValue in vRow) vTotal += vValue
	}
	println(vTotal)

	println(kept(6).joinToString())

}
