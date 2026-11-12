// Benchmark: lists and maps, loops over them, the functional operators of the library.

/** What is counted per word. */
class Entry(val key: String, var count: Int)

fun main() {
	val vNumbers = ArrayList<Int>()
	for (vI in 0 until 200000) vNumbers.add((vI * 31) % 1000)
	var vSum = 0L
	for (vRound in 0 until 100) {
		for (vNumber in vNumbers) vSum += vNumber
		vSum += vNumbers.filter { it % 3 == 0 }.map { it * 2 }.sum()
	}
	println(vSum)

	val vWords = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa")
	val vCounts = HashMap<String, Entry>()
	for (vI in 0 until 3000000) {
		val vWord = vWords[(vI * 7) % vWords.size]
		val vEntry = vCounts.getOrPut(vWord) { Entry(vWord, 0) }
		vEntry.count++
	}
	for (vWord in vWords) println("$vWord ${vCounts[vWord]?.count}")

	val vById = HashMap<Int, Int>()
	for (vI in 0 until 200000) vById[vI] = vI * 2
	var vHits = 0L
	for (vRound in 0 until 5) for (vI in 0 until 400000) vHits += vById[vI] ?: -1
	println(vHits)

	val vEntries = ArrayList<Entry>()
	for (vI in 0 until 50000) vEntries.add(Entry("k${vI % 100}", vI))
	var vBest = 0
	for (vRound in 0 until 200) {
		vBest += vEntries.maxOf { it.count } + vEntries.count { it.count % 2 == 0 }
		vEntries.forEach { it.count += 1 }
	}
	println(vBest)
	println(vEntries.sortedBy { it.key }.first().key)
}
