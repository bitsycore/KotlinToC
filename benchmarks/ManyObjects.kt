// Benchmark: a map of many objects. A million records are put in a HashMap by key, looked up, changed
// and removed in part; then grouped and sorted: the memory of many live objects, and hashing.

/** A record of the map: a key, a name made of it, and a count that changes. */
class Account(val id: Int, val name: String, var balance: Long)

fun main() {
	val vCount = 1_000_000
	val vAccounts = HashMap<Int, Account>()
	for (vI in 0 until vCount) vAccounts[vI * 7] = Account(vI * 7, "account-$vI", (vI % 1000).toLong())
	println(vAccounts.size)

	// Look up keys that are there and keys that are not, and change what is found.
	var vFound = 0
	for (vI in 0 until vCount * 2) {
		val vAccount = vAccounts[vI * 3]
		if (vAccount != null) {
			vAccount.balance += vI % 10
			vFound++
		}
	}
	println(vFound)

	// Remove one in five.
	for (vI in 0 until vCount step 5) vAccounts.remove(vI * 7)
	println(vAccounts.size)

	// Group by the last digit of the balance, and sort each group's names.
	val vGroups = vAccounts.values.groupBy { it.balance % 10 }
	var vTotal = 0L
	for ((vDigit, vGroup) in vGroups.toSortedMap2()) {
		val vFirst = vGroup.map { it.name }.sorted().first()
		vTotal += vGroup.sumOf { it.balance }
		println("$vDigit ${vGroup.size} $vFirst")
	}
	println(vTotal)
}

/** The entries of this map sorted by key (no JDK: `toSortedMap` is the JVM's own). */
fun <V> Map<Long, V>.toSortedMap2(): List<Pair<Long, V>> = entries.sortedBy { it.key }.map { it.key to it.value }
