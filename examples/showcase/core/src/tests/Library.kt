package showcase.core.tests

import showcase.core.Group

// ==================
// MARK: Collections
// ==================

private data class Order(val customer: String, val item: String, val amount: Int)

fun collectionsGroup(): Group = Group("Collections") { r ->
	val vOrders = listOf(
		Order("ann", "tea", 3), Order("bob", "milk", 1), Order("ann", "milk", 2),
		Order("cid", "tea", 7), Order("bob", "tea", 4), Order("ann", "jam", 1),
	)
	r.expect("filter and map", listOf("tea", "milk", "jam"), vOrders.filter { it.customer == "ann" }.map { it.item })
	r.expect("groupBy and sum", "{ann=6, bob=5, cid=7}", vOrders.groupBy { it.customer }.mapValues { vEntry -> vEntry.value.sumOf { it.amount } }.toString())
	r.expect("sorting", "cid bob ann", vOrders.sortedWith(compareByDescending<Order> { it.amount }.thenBy { it.customer }).map { it.customer }.distinct().joinToString(" "))
	r.expect("fold and reduce", "18 7", "${vOrders.fold(0) { vSum, vOrder -> vSum + vOrder.amount }} ${vOrders.map { it.amount }.reduce { vBest, vAmount -> maxOf(vBest, vAmount) }}")
	r.expect("partition and zip", "[(tea, 3), (tea, 7), (tea, 4)] 3", vOrders.partition { it.item == "tea" }.let { (vTea, vOther) -> "${vTea.map { it.item to it.amount }} ${vOther.size}" })
	r.expect("any, all, none", "true false true", "${vOrders.any { it.amount > 6 }} ${vOrders.all { it.amount > 1 }} ${vOrders.none { it.item == "salt" }}")

	val vStock = HashMap<String, Int>()
	for (vOrder in vOrders) vStock[vOrder.item] = (vStock[vOrder.item] ?: 0) + vOrder.amount
	vStock.remove("jam")
	r.expect("mutable map", "14 3 null true", "${vStock["tea"]} ${vStock.getValue("milk")} ${vStock["jam"]} ${"tea" in vStock}")
	val vItems = LinkedHashSet<String>()
	vOrders.forEach { vItems.add(it.item) }
	r.expect("sets", "[tea, milk, jam] [milk] [jam, milk, tea]", "$vItems ${vItems intersect setOf("milk", "salt")} ${vItems.sorted()}")

	val vQueue = ArrayList<Int>()
	for (vValue in 1..6) vQueue.add(vValue * vValue)
	vQueue.removeAt(0)
	vQueue.add(2, 100)
	vQueue[0] = -1
	r.expect("mutable list", "[-1, 9, 100, 16, 25, 36] 2 5", "$vQueue ${vQueue.indexOf(100)} ${vQueue.lastIndex}")
	r.expect("sublists and chunks", "[9, 100] [[1, 2], [3, 4], [5]] [3, 2, 1]", "${vQueue.subList(1, 3)} ${listOf(1, 2, 3, 4, 5).chunked(2)} ${listOf(1, 2, 3).reversed()}")

	val vSquares = generateSequence(1) { it + 1 }.map { it * it }.filter { it % 2 == 1 }.take(4).toList()
	r.expect("sequences", listOf(1, 9, 25, 49), vSquares)
	val vNumbers = intArrayOf(5, 3, 9, 1, 7)
	val vSorted = vNumbers.sortedArray()
	r.expect("arrays", "[1, 3, 5, 7, 9] 25 9 true", "${vSorted.contentToString()} ${vNumbers.sum()} ${vNumbers.max()} ${3 in vNumbers}")
	val vGrid = Array(3) { vRow -> IntArray(3) { vColumn -> vRow * 3 + vColumn } }
	r.expect("arrays of arrays", "12 [2, 5, 8]", "${vGrid.sumOf { it[1] }} ${vGrid.map { it.last() }}")
	r.expect("ranges", "[10, 7, 4, 1] 55 true", "${(10 downTo 1 step 3).toList()} ${(1..10).sum()} ${'k' in 'a'..'z'}")
	r.expect("pairs and maps", "{one=1, two=2} [1=one, 2=two]", "${mapOf("one" to 1, "two" to 2)} ${mapOf(1 to "one", 2 to "two").entries.map { "${it.key}=${it.value}" }}")
}

// ==================
// MARK: Text
// ==================

fun textGroup(): Group = Group("Text") { r ->
	val vLine = "  The quick brown fox, the lazy dog; and 42 others.  "
	val vWords = vLine.trim().split(Regex("[ ,;.]+")).filter { it.isNotEmpty() }
	r.expect("split and trim", "10 The others", "${vWords.size} ${vWords.first()} ${vWords.last()}")
	r.expect("case and search", "THE QUICK 6 true", vLine.trim().substring(0, 9).uppercase() + " " + vLine.indexOf("quick") + " " + vLine.contains("LAZY", ignoreCase = true))
	r.expect("replace and repeat", "The-quick-brown ab-ab-ab", vWords.take(3).joinToString("-") + " " + List(3) { "ab" }.joinToString("-"))
	r.expect("padding and numbers", "007 2a 101010 3.5", "7".padStart(3, '0') + " " + 42.toString(16) + " " + 42.toString(2) + " " + "3.5".toDouble())
	r.expect("parsing", "42 null -17 255", "${"42".toInt()} ${"4x2".toIntOrNull()} ${"-17".toLong()} ${"ff".toInt(16)}")

	val vBuilder = StringBuilder()
	for (vIndex in 1..5) vBuilder.append(vIndex).append(if (vIndex < 5) "," else "")
	vBuilder.insert(0, "[").append("]").setLength(vBuilder.length)
	r.expect("string builder", "[1,2,3,4,5] 11", "$vBuilder ${vBuilder.length}")
	r.expect("templates", "3 + 4 = 7, fox has 3 letters", "${1 + 2} + ${2 * 2} = ${3 + 4}, ${vWords[3]} has ${vWords[3].length} letters")
	r.expect("lines", "3 [alpha, , gamma]", "alpha\n\ngamma".lines().let { "${it.size} $it" })

	val vDate = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
	val vText = "from 2024-02-29 to 2025-12-31, not 99-1-1"
	r.expect("regex find", "[2024-02-29, 2025-12-31]", vDate.findAll(vText).map { it.value }.toList().toString())
	r.expect("regex groups", "29/02/2024", vDate.find(vText)!!.destructured.let { (vYear, vMonth, vDay) -> "$vDay/$vMonth/$vYear" })
	r.expect("regex replace", "from <2024> to <2025>, not 99-1-1", vDate.replace(vText) { "<" + it.groupValues[1] + ">" })
	r.expect("regex match", "true false", "${Regex("[a-z]+\\d?").matches("abc7")} ${Regex("(?i)hello").containsMatchIn("say HELLO") && Regex("\\p{Lu}").containsMatchIn("abc")}")

	r.expect("unicode case", "ÉCOLE STRASSE öl", "école".uppercase() + " " + "straße".uppercase() + " " + "ÖL".lowercase())
	r.expect("unicode classes", "true true false 5", "${'é'.isLetter()} ${'٣'.isDigit()} ${'€'.isLetterOrDigit()} ${"naïve".length}")
	r.expect("characters", "[h, e, l, l, o] 104 i true", "${"hello".toList()} ${'h'.code} ${'h' + 1} ${'7'.isDigit()}")
	r.expect("comparing", "-1 0 true", "${"apple".compareTo("banana").coerceIn(-1, 1)} ${"a".compareTo("A", ignoreCase = true)} ${"abc" < "abd"}")
}
