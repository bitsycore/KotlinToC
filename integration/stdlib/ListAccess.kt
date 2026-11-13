// `list[i]`, `list[i] = x` and `list.size` on a list known to be an ArrayList, with indices in
// range and out of it, and with the list changed on the way.

class Name(val text: String) {
	override fun toString(): String = text
}

var gCalls = 0

fun next(): Int = gCalls++

fun attempt(inWhat: String, inBody: () -> Any?) {
	try {
		println("$inWhat: ${inBody()}")
	} catch (inError: IndexOutOfBoundsException) {
		println("$inWhat: out of bounds, ${inError.message}")
	}
}

fun sumTo(inList: ArrayList<Int>, inCount: Int): Int {
	var vSum = 0
	for (vIndex in 0 until inCount) vSum += inList[vIndex]
	return vSum
}

fun viaInterface(inList: List<Name>, inIndex: Int): String = inList[inIndex].text + inList.size

fun main() {
	val vNumbers = arrayListOf(10, 20, 30)
	attempt("first") { vNumbers[0] }
	attempt("last") { vNumbers[vNumbers.size - 1] }
	attempt("at size") { vNumbers[vNumbers.size] }
	attempt("negative") { vNumbers[-1] }
	attempt("far") { vNumbers[Int.MAX_VALUE] }
	attempt("set at size") { vNumbers[3] = 1 }
	attempt("set negative") { vNumbers[-2] = 1 }
	attempt("sum of 3") { sumTo(vNumbers, 3) }
	attempt("sum of 4") { sumTo(vNumbers, 4) }
	attempt("empty") { ArrayList<Int>()[0] }

	// The index and the value are computed once, in order, before the test.
	attempt("counted") { vNumbers[next()] + vNumbers[next()] }
	attempt("counted out") { vNumbers[next() + 5] }
	vNumbers[next() - 3] = next() * 100
	println("$vNumbers $gCalls")
	vNumbers[0] = vNumbers[2]
	vNumbers[2] = vNumbers[1] + vNumbers[0]
	vNumbers[1]++
	vNumbers[1] += 5
	println("$vNumbers ${vNumbers.size}")

	// A list that shrinks under a loop that read its size before.
	val vShrinking = arrayListOf(1, 2, 3, 4, 5, 6)
	attempt("shrinking") {
		var vSum = 0
		for (vIndex in 0 until vShrinking.size) {
			if (vIndex == 2) vShrinking.removeAt(0)
			vSum += vShrinking[vIndex]
			if (vIndex == 3) vShrinking.clear()
		}
		vSum
	}
	println(vShrinking.size)

	// A variable that is given another list between two reads.
	var vCurrent = arrayListOf(1, 2, 3)
	val vFirst = vCurrent[1]
	vCurrent = arrayListOf(7)
	attempt("other list") { vFirst + vCurrent[0] + vCurrent.size }
	attempt("other list, out") { vCurrent[1] }

	// Objects: what is read stays alive, what is replaced is given up.
	val vNames = ArrayList<Name>()
	for (vIndex in 0 until 4) vNames += Name("n$vIndex")
	val vKept = vNames[1]
	vNames[1] = Name("replaced")
	vNames[2] = vNames[3]
	vNames[0] = vNames[0]
	println("$vKept $vNames ${vNames.size}")
	val vTaken = vNames[3]
	vNames.clear()
	println("$vTaken $vKept ${vNames.size}")
	attempt("cleared") { vNames[0] }
	vNames += Name("again")
	attempt("interface") { viaInterface(vNames, 0) }
	attempt("interface out") { viaInterface(vNames, 1) }

	// Lists of lists, of numbers that may be null, of strings.
	val vGrid = ArrayList<ArrayList<Int>>()
	for (vRow in 0 until 3) {
		vGrid += ArrayList<Int>()
		for (vColumn in 0 until 3) vGrid[vRow] += vRow * 3 + vColumn
	}
	vGrid[1][1] = vGrid[0][2] + vGrid[2][0]
	println("$vGrid ${vGrid[2].size} ${vGrid.size}")
	attempt("grid row out") { vGrid[3][0] }
	attempt("grid column out") { vGrid[0][3] }
	val vOptional = arrayListOf<Int?>(1, null, 3)
	vOptional[0] = vOptional[1]
	vOptional[1] = 5
	println("$vOptional ${vOptional[0] == null} ${vOptional[2]!! + 1}")
	val vWords = arrayListOf("a", "b")
	vWords[0] = vWords[1] + vWords[0]
	println("$vWords ${vWords[0].length}")
	val vReals = arrayListOf(1.5, 2.5)
	vReals[1] = vReals[0] * vReals[1]
	println(vReals)
}
