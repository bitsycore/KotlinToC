package stdlib

data class Person(val name: String, val age: Int)

fun describe(numbers: List<Int>): String = "size=${numbers.size} first=${numbers.first()} last=${numbers.last()}"

fun main() {
	// Lists
	val vNumbers = mutableListOf(5, 3, 8, 1)
	vNumbers.add(9)
	vNumbers.add(0, 7)
	println(vNumbers)
	println(describe(vNumbers))
	println(vNumbers.contains(8))
	println(vNumbers.indexOf(3))
	vNumbers.remove(8)
	vNumbers.removeAt(0)
	vNumbers[1] = 30
	println(vNumbers)
	println(vNumbers.isEmpty())
	val vEmpty = emptyList<String>()
	println(vEmpty)
	println(vEmpty.isEmpty())
	val vNames = listOf("delta", "alpha", "charlie", "bravo")
	println(vNames)
	println(vNames[2])
	println(listOf(1.5, 2.5))
	println(listOf("only"))
	val vList = ArrayList<String>()
	vList.add("x")
	vList.addAll(vNames)
	println(vList.size)
	println(vList.subList(1, 3))

	// Transformations
	println(vNumbers.map { it * 2 })
	println(vNumbers.filter { it > 3 })
	println(vNumbers.sum())
	println(vNames.map { it.length })
	println(vNames.filter { it.length == 5 }.map { it + "!" })
	println(vNumbers.any { it > 20 })
	println(vNumbers.all { it > 20 })
	println(vNumbers.none { it > 100 })
	println(vNumbers.count { it % 2 == 1 })
	println(vNumbers.firstOrNull { it > 100 })
	println(vNames.first { it.length > 5 })
	println(vNumbers.fold(0) { vAccumulator, vValue -> vAccumulator + vValue })
	println(vNumbers.reversed())
	println(vNumbers.take(2))
	println(vNumbers.drop(2))
	println(vNames.contains("alpha"))
	var vTotal = 0
	vNumbers.forEach { vTotal += it }
	println(vTotal)
	vNames.forEachIndexed { vIndex, vName -> print("$vIndex=$vName ") }
	println()
	for ((vIndex, vValue) in vNumbers.withIndex()) print("$vIndex:$vValue ")
	println()
	println(vNumbers.maxOrNull())
	println(vNumbers.minOrNull())
	println(vNames.joinToString(", "))
	println(vNumbers.joinToString(separator = "-", prefix = "<", postfix = ">"))
	println(vNames.joinToString { "<$it>" })

	// Sorting
	println(vNumbers.sorted())
	println(vNames.sorted())
	val vMutable = mutableListOf(4, 2, 9, 1)
	vMutable.sort()
	println(vMutable)
	vMutable.reverse()
	println(vMutable)

	// Objects in lists
	val vPeople = listOf(Person("ann", 31), Person("bob", 25), Person("cy", 40))
	println(vPeople.map { it.name })
	println(vPeople.filter { it.age > 30 })
	println(vPeople.sumOf { it.age })
	println(vPeople.first { it.age < 30 })
	println(vPeople.contains(Person("bob", 25)))

	// Maps
	val vAges = mutableMapOf<String, Int>()
	vAges["ann"] = 31
	vAges["bob"] = 25
	vAges["ann"] = 32
	println(vAges)
	println(vAges["ann"])
	println(vAges["zed"])
	println(vAges.size)
	println(vAges.containsKey("bob"))
	println(vAges.getOrElse("zed") { -1 })
	for ((vKey, vValue) in vAges) print("$vKey->$vValue ")
	println()
	println(vAges.keys)
	println(vAges.values)
	vAges.remove("bob")
	println(vAges)
	val vLookup = mapOf("one" to 1, "two" to 2, "three" to 3)
	println(vLookup)
	println(vLookup["two"])
	println(vLookup.filter { it.value > 1 }.keys)
	println(vLookup.map { "${it.key}=${it.value * 10}" })
	val vByLength = HashMap<Int, String>()
	vByLength[3] = "abc"
	vByLength[1] = "a"
	println(vByLength[3] + vByLength[1])
	for (vI in 0 until 100) vByLength[vI] = "v$vI"
	println(vByLength.size)
	println(vByLength[57])

	// Sets
	val vSeen = mutableSetOf<String>()
	println(vSeen.add("a"))
	println(vSeen.add("a"))
	vSeen.add("b")
	println(vSeen)
	println("b" in vSeen)
	println(setOf(3, 1, 3, 2))
	println(vNumbers.toSet().size)
	println(listOf(1, 2, 2, 3, 3, 3).distinct())

	// StringBuilder and buildString
	val vBuilder = StringBuilder()
	vBuilder.append("count: ").append(3).append(' ').append(1.5).append(true)
	println(vBuilder.toString())
	println(vBuilder.length)
	println(buildString {
		append("a")
		for (vI in 1..3) append(vI)
	})
}
