// What ordinary code does with lists, sets, maps, arrays and sequences.

data class Item(val name: String, val price: Double, val count: Int)

fun main() {
	val vNumbers = mutableListOf(5, 3, 8, 1, 9, 2)
	vNumbers.add(2, 7)
	vNumbers.addAll(listOf(4, 6))
	vNumbers.addAll(0, listOf(0))
	vNumbers.removeAll(listOf(9, 100))
	vNumbers.retainAll { it != 8 }
	vNumbers.removeAll { it == 0 }
	println("$vNumbers ${vNumbers.indexOf(7)} ${vNumbers.lastIndexOf(42)} ${vNumbers.contains(4)} ${vNumbers.containsAll(listOf(1, 2))} ${5 in vNumbers}")
	println("${vNumbers.sorted()} ${vNumbers.sortedDescending()} ${vNumbers.reversed()} ${vNumbers.asReversed()} ${vNumbers.shuffled().size}")
	println("${vNumbers.first()} ${vNumbers.last()} ${vNumbers.firstOrNull { it > 6 }} ${vNumbers.lastOrNull { it > 60 }} ${vNumbers.single { it == 7 }} ${vNumbers.elementAtOrNull(50)} ${vNumbers.getOrElse(50) { -1 }} ${vNumbers.getOrNull(1)}")
	println("${vNumbers.sum()} ${vNumbers.average()} ${vNumbers.max()} ${vNumbers.minOrNull()} ${vNumbers.maxByOrNull { -it }} ${vNumbers.sumOf { it * 1.5 }} ${vNumbers.count { it % 2 == 0 }} ${vNumbers.fold(1L) { vAll, vOne -> vAll * vOne }} ${vNumbers.reduce { vAll, vOne -> vAll - vOne }}")
	println("${vNumbers.take(2)} ${vNumbers.drop(5)} ${vNumbers.takeLast(2)} ${vNumbers.dropLast(5)} ${vNumbers.takeWhile { it < 8 }} ${vNumbers.dropWhile { it < 8 }} ${vNumbers.slice(1..3)} ${vNumbers.subList(1, 3)}")
	println("${vNumbers.chunked(3)} ${vNumbers.windowed(3)} ${vNumbers.windowed(2, 2, true)} ${vNumbers.zipWithNext()} ${vNumbers.zip(listOf("a", "b"))} ${vNumbers.withIndex().first()}")
	println("${vNumbers.map { it * 2 }} ${vNumbers.mapIndexed { vIndex, vValue -> vIndex * vValue }} ${vNumbers.mapNotNull { if (it > 4) it else null }} ${vNumbers.flatMap { listOf(it, -it) }.take(4)} ${vNumbers.filter { it > 4 }} ${vNumbers.filterNot { it > 4 }} ${vNumbers.filterIndexed { vIndex, _ -> vIndex % 2 == 0 }} ${vNumbers.partition { it > 4 }}")
	println("${vNumbers.any { it > 6 }} ${vNumbers.all { it > 6 }} ${vNumbers.none { it > 60 }} ${vNumbers.distinct().size} ${listOf(1, 1, 2).distinct()} ${vNumbers.groupBy { it % 3 }} ${vNumbers.associateWith { it * it }.entries.first()} ${vNumbers.associateBy { it % 2 }} ${vNumbers.associate { it to "n$it" }[7]}")
	println("${vNumbers.joinToString()} ${vNumbers.joinToString("-", "<", ">", 3, "more") { "#$it" }} ${vNumbers.binarySearch(4)} ${vNumbers.sorted().binarySearch(4)} ${vNumbers.toIntArray().sum()} ${vNumbers.isEmpty()} ${vNumbers.isNotEmpty()} ${vNumbers.indices} ${vNumbers.lastIndex}")
	val vIterator = vNumbers.listIterator()
	while (vIterator.hasNext()) { val vValue = vIterator.next(); if (vValue % 2 == 0) vIterator.set(vValue * 10) else if (vValue == 7) vIterator.remove() }
	while (vIterator.hasPrevious()) if (vIterator.previous() == 5) vIterator.add(55)
	println(vNumbers)
	vNumbers.sortWith(compareBy<Int> { it % 10 }.thenByDescending { it })
	vNumbers.swap(0, 1)
	println("$vNumbers ${vNumbers.removeFirst()} ${vNumbers.removeLast()} ${vNumbers.removeAt(0)} $vNumbers")
	try { emptyList<Int>().first() } catch (inError: NoSuchElementException) { println("empty: ${inError.message}") }
	try { listOf(1, 2).single() } catch (inError: IllegalArgumentException) { println("two: ${inError.message}") }
	try { listOf(1)[3] } catch (inError: IndexOutOfBoundsException) { println("bounds") }
	try { emptyList<Int>().reduce { vA, vB -> vA + vB } } catch (inError: UnsupportedOperationException) { println("reduce: ${inError.message}") }

	val vItems = listOf(Item("pen", 1.5, 10), Item("book", 12.0, 2), Item("ink", 4.25, 0), Item("pad", 1.5, 7))
	println(vItems.sortedBy { it.price }.map { it.name })
	println(vItems.sortedWith(compareBy({ it.price }, { it.name })).map { it.name })
	println(vItems.sortedByDescending { it.count }.first())
	println("${vItems.maxOf { it.price }} ${vItems.minOf { it.count }} ${vItems.sumOf { it.count }} ${vItems.sumOf { it.price * it.count }} ${vItems.groupingBy { it.price }.eachCount()} ${vItems.filter { it.count > 0 }.map { it.name }.sorted()}")
	println(vItems.groupBy({ it.price }, { it.name }))
	println(vItems.associateBy({ it.name }, { it.count }).filterValues { it > 5 }.keys)

	val vSet = mutableSetOf(3, 1, 2, 3)
	vSet += 5
	vSet -= 1
	println("$vSet ${vSet.size} ${2 in vSet} ${vSet union setOf(9, 2)} ${vSet intersect setOf(2, 5, 8)} ${vSet subtract setOf(2)} ${vSet.minOrNull()} ${setOf(1, 2) == setOf(2, 1)} ${listOf(1, 2) == listOf(2, 1)} ${hashSetOf("b", "a").sorted()}")
	val vOrdered = linkedSetOf("z", "a", "m")
	vOrdered.remove("a"); vOrdered.add("a"); vOrdered.add("z")
	println("$vOrdered ${vOrdered.first()} ${vOrdered.toList().reversed()} ${vOrdered.map { it.uppercase() }.toSet()}")

	val vMap = mutableMapOf("one" to 1, "two" to 2)
	vMap["three"] = 3
	vMap.putAll(mapOf("four" to 4, "one" to 11))
	vMap.remove("two")
	println("$vMap ${vMap["one"]} ${vMap["none"]} ${vMap.getOrElse("none") { -1 }} ${vMap.getOrElse("x") { 0 }} ${vMap.getOrPut("five") { 5 }} ${vMap.getValue("four")} ${"three" in vMap} ${vMap.containsValue(3)} ${vMap.keys} ${vMap.values} ${vMap.size}")
	println("${vMap.filter { it.value > 3 }} ${vMap.mapValues { it.value * 2 }} ${vMap.mapKeys { it.key.length }} ${vMap.map { "${it.key}=${it.value}" }} ${vMap.toList().first()} ${vMap.entries.sumOf { it.value }} ${vMap.maxByOrNull { it.value }?.key} ${vMap.any { it.key.length == 3 }} ${vMap.count()} ${vMap.keys.sorted()}")
	for ((vKey, vValue) in vMap) if (vValue > 4) vMap[vKey] = vValue - 1
	for (vEntry in vMap.entries) if (vEntry.key == "three") vEntry.setValue(33)
	val vEntries = vMap.entries.iterator()
	while (vEntries.hasNext()) if (vEntries.next().value == 4) vEntries.remove()
	println("$vMap ${vMap + ("z" to 0)} ${vMap - "one"} ${vMap.keys.sorted()} ${vMap.entries.sortedBy { it.value }.map { it.key }}")
	try { vMap.getValue("missing") } catch (inError: NoSuchElementException) { println(inError.message) }
	val vCounts = HashMap<Char, Int>()
	for (vChar in "mississippi") vCounts[vChar] = (vCounts[vChar] ?: 0) + 1
	println("${vCounts.toList().sortedBy { it.first }} ${vCounts.entries.sortedWith(compareByDescending<Map.Entry<Char, Int>> { it.value }.thenBy { it.key }).first().key} ${"mississippi".groupingBy { it }.eachCount()['s']}")
	val vNested = HashMap<String, MutableList<Int>>()
	vNested.getOrPut("a") { mutableListOf() }.add(1)
	vNested.getOrPut("a") { mutableListOf() }.add(2)
	vNested.getOrPut("b") { ArrayList() } += 3
	println("$vNested ${vNested.values.flatten()} ${vNested.mapValues { it.value.sum() }}")

	val vDeque = ArrayDeque<Int>()
	vDeque.addLast(1); vDeque.addFirst(0); vDeque.addLast(2); vDeque.add(1, 9)
	println("$vDeque ${vDeque.removeFirst()} ${vDeque.removeLast()} ${vDeque.first()} ${vDeque.last()} ${vDeque.size} ${vDeque.removeFirstOrNull()} ${vDeque.removeLastOrNull()} ${vDeque.removeFirstOrNull()} ${vDeque.isEmpty()}")

	val vArray = intArrayOf(4, 2, 9)
	val vCopy = vArray.copyOf(5)
	vArray.sort()
	vCopy.fill(7, 3, 5)
	println("${vArray.contentToString()} ${vCopy.toList()} ${vArray.contentEquals(intArrayOf(2, 4, 9))} ${vArray.copyOfRange(1, 3).toList()} ${vArray.sum()} ${vArray.average()} ${vArray.indexOf(9)} ${vArray.reversedArray().toList()} ${vArray.map { it * 2 }} ${vArray.sliceArray(0..1).size} ${vArray.max()} ${(vArray + 1).size} ${vArray.sortedDescending()}")
	val vWords = arrayOf("pear", "fig", "apple")
	vWords.sort()
	println("${vWords.joinToString()} ${vWords.contentToString()} ${vWords.size} ${vWords.map { it.length }} ${vWords.indexOf("fig")} ${"fig" in vWords} ${vWords.toList() == listOf("apple", "fig", "pear")} ${Array(3) { it * it }.toList()} ${arrayOfNulls<String>(2).toList()} ${doubleArrayOf(1.5, 2.5).sum()} ${booleanArrayOf(true, false).count { it }} ${charArrayOf('b', 'a').sorted()} ${longArrayOf(1L, 2L).last()}")
	val vGrid = Array(3) { vRow -> IntArray(3) { vColumn -> vRow * 3 + vColumn } }
	println("${vGrid[1][2]} ${vGrid.sumOf { it.sum() }} ${vGrid.map { it.toList() }} ${vGrid.flatMap { it.asList() }.filter { it % 2 == 0 }}")

	val vSequence = generateSequence(1) { it * 2 }
	println("${vSequence.take(8).toList()} ${vSequence.takeWhile { it < 50 }.sum()} ${vSequence.map { it + 1 }.filter { it % 3 == 0 }.first()} ${vSequence.drop(3).first()} ${(1..10).asSequence().map { it * it }.filter { it % 2 == 1 }.toList()} ${sequenceOf(3, 1, 2).sorted().toList()} ${listOf(1, 2, 3).asSequence().zip(sequenceOf("a", "b")).toList()} ${generateSequence(10) { if (it > 7) it - 1 else null }.toList()} ${(1..4).asSequence().flatMap { sequenceOf(it, it) }.distinct().toList()}")
	println("${(1..10).filter { it % 3 == 0 }} ${(10 downTo 1 step 3).toList()} ${(1 until 10 step 4).map { it }} ${('a'..'e').joinToString("")} ${(1..3).reversed().toList()} ${(0..9).sum()} ${5 in 1..9} ${(1..0).isEmpty()} ${(1L..3L).toList()} ${(1..10).random() in 1..10} ${listOf(1, 2, 3).indices.last} ${(1..4).fold("") { vText, vValue -> vText + vValue }} ${IntRange(2, 4).count()} ${(1.5..2.5).contains(2.0)} ${(3 downTo 1).first}")
	println("${listOf(listOf(1), listOf(2, 3)).flatten()} ${listOf(1, 2) + 3} ${listOf(1, 2) + listOf(3)} ${listOf(1, 2, 1) - 1} ${listOf("b", "a").sortedWith(String.CASE_INSENSITIVE_ORDER)} ${listOf(1 to "a").toMap()} ${mapOf(1 to 2).toList()} ${listOf<Int>().sum()} ${emptyList<String>().joinToString()} ${listOfNotNull(1, null, 2)} ${List(3) { "x$it" }} ${MutableList(2) { 0 }} ${buildList { add(1); addAll(listOf(2, 3)) }} ${buildMap { put("k", 1) }} ${buildSet { add(1); add(1) }}")
}

fun <T> MutableList<T>.swap(inFirst: Int, inSecond: Int) { val vHeld = this[inFirst]; this[inFirst] = this[inSecond]; this[inSecond] = vHeld }
