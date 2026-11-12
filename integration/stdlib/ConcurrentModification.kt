// A list or a map changed while it is gone over, otherwise than through the iterator, fails
// at the next step - except where the JVM does not notice either.

fun attempt(inWhat: String, inBlock: () -> Any?) {
	try {
		println("$inWhat: ${inBlock()}")
	} catch (inError: ConcurrentModificationException) {
		println("$inWhat: ConcurrentModificationException")
	}
}

fun main() {
	attempt("add while iterating") {
		val vList = arrayListOf(1, 2, 3)
		for (vItem in vList) if (vItem == 1) vList.add(4)
		vList
	}
	attempt("remove while iterating") {
		val vList = arrayListOf(1, 2, 3, 4)
		for (vItem in vList) if (vItem == 2) vList.remove(vItem)
		vList
	}
	// The last but one: the loop ends before it looks again.
	attempt("remove the last but one") {
		val vList = arrayListOf(1, 2, 3)
		for (vItem in vList) if (vItem == 2) vList.remove(vItem)
		vList
	}
	attempt("remove the last") {
		val vList = arrayListOf(1, 2, 3)
		for (vItem in vList) if (vItem == 3) vList.remove(vItem)
		vList
	}
	attempt("clear while iterating") {
		val vList = arrayListOf(1, 2, 3)
		for (vItem in vList) if (vItem == 1) vList.clear()
		vList
	}
	attempt("set while iterating") {
		val vList = arrayListOf(1, 2, 3)
		for (vItem in vList) if (vItem == 1) vList[2] = 30
		vList
	}
	attempt("remove through the iterator") {
		val vList = arrayListOf(1, 2, 3, 4)
		val vIterator = vList.iterator()
		while (vIterator.hasNext()) if (vIterator.next() % 2 == 0) vIterator.remove()
		vList
	}
	attempt("add through the list iterator") {
		val vList = arrayListOf(1, 2, 3)
		val vIterator = vList.listIterator()
		while (vIterator.hasNext()) if (vIterator.next() == 2) vIterator.add(20)
		vList
	}
	attempt("two iterators, one removes") {
		val vList = arrayListOf(1, 2, 3)
		val vFirst = vList.iterator()
		val vSecond = vList.iterator()
		vFirst.next()
		vFirst.remove()
		vSecond.next()
	}
	attempt("removeAll that removes nothing") {
		val vList = arrayListOf(1, 2, 3)
		var vSum = 0
		for (vItem in vList) {
			vList.removeAll(listOf(9))
			vSum += vItem
		}
		vSum
	}

	attempt("sort while iterating") {
		val vList = arrayListOf(3, 1, 2)
		for (vItem in vList) if (vItem == 3) vList.sort()
		vList
	}
	attempt("sort a list of one while iterating") {
		val vList = arrayListOf(1)
		for (vItem in vList) vList.sort()
		vList
	}

	attempt("put a new key while iterating") {
		val vMap = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
		for (vKey in vMap.keys) if (vKey == "a") vMap["d"] = 4
		vMap
	}
	attempt("put an existing key while iterating") {
		val vMap = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
		for (vKey in vMap.keys) vMap[vKey] = 10
		vMap
	}
	attempt("remove a key while iterating") {
		val vMap = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
		for ((vKey, _) in vMap) if (vKey == "a") vMap.remove("c")
		vMap
	}
	// At the last entry the map is not looked at again.
	attempt("put a new key at the last entry") {
		val vMap = linkedMapOf("a" to 1, "b" to 2)
		for (vKey in vMap.keys) if (vKey == "b") vMap["c"] = 3
		vMap
	}
	attempt("remove through the entries") {
		val vMap = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
		val vIterator = vMap.entries.iterator()
		while (vIterator.hasNext()) if (vIterator.next().value == 2) vIterator.remove()
		vMap
	}
	attempt("a set changed while iterating") {
		val vSet = linkedSetOf(1, 2, 3)
		for (vItem in vSet) if (vItem == 1) vSet.add(4)
		vSet
	}
	attempt("a hash map with one key") {
		val vMap = hashMapOf("only" to 1)
		for (vKey in vMap.keys) vMap["other"] = 2
		vMap.size
	}
}
