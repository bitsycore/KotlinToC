package objects

class Matrix(val rows: Int, val columns: Int) {
	private val mCells = IntArray(rows * columns)

	operator fun get(inRow: Int, inColumn: Int): Int = mCells[inRow * columns + inColumn]

	operator fun set(inRow: Int, inColumn: Int, inValue: Int) {
		mCells[inRow * columns + inColumn] = inValue
	}
}

fun average(inValues: IntArray, inCount: Int): Int {
	var vSum = 0
	for (vIndex in 0 until inCount) vSum += inValues[vIndex]
	return vSum / inCount
}

fun describe(inAction: () -> Any?): String = try {
	"ok ${inAction()}"
} catch (vError: ArrayIndexOutOfBoundsException) {
	"array: ${vError.message}"
} catch (vError: StringIndexOutOfBoundsException) {
	"string: ${vError.message}"
} catch (vError: IndexOutOfBoundsException) {
	"index"
} catch (vError: ArithmeticException) {
	"arithmetic: ${vError.message}"
}

fun firstMatch(inValues: IntArray, inFrom: Int, inWanted: Int): Boolean =
	inFrom < inValues.size && inValues[inFrom] == inWanted

fun pick(inValues: IntArray, inIndex: Int, inUseIt: Boolean): Int = if (inUseIt) inValues[inIndex] else -1

fun main() {
	val vNumbers = intArrayOf(10, 20, 30)
	val vEmpty = IntArray(0)
	val vWords = arrayOf("a", "b")

	// Reading and writing outside an array.
	println(describe { vNumbers[1] })
	println(describe { vNumbers[3] })
	println(describe { vNumbers[-1] })
	println(describe { vEmpty[0] })
	println(describe { vWords[2] })
	println(describe { vNumbers[5] = 1 })
	println(describe { vNumbers[vNumbers[0]] })
	println(vNumbers[0] + vNumbers[1] + vNumbers[2])

	// The failure leaves through the functions in between, and a finally still runs.
	println(describe { average(vNumbers, 3) })
	println(describe { average(vNumbers, 4) })
	println(describe { average(vNumbers, 0) })
	val vMatrix = Matrix(2, 2)
	vMatrix[1, 1] = 7
	println(describe { vMatrix[1, 1] })
	println(describe { vMatrix[2, 0] })
	try {
		try {
			vMatrix[0, 9] = 1
		} finally {
			println("finally")
		}
	} catch (vError: IndexOutOfBoundsException) {
		println("caught outside")
	}

	// Division and remainder, of each integer width.
	val vZero = vNumbers[0] - 10
	println(describe { 7 / vZero })
	println(describe { 7 % vZero })
	println(describe { 7L / vZero })
	println(describe { 7 / 2 })
	println(describe { 7.0 / vZero })
	println(describe { (-7) % 2 })

	// Strings.
	val vText = "héllo"
	println(describe { vText[1] })
	println(describe { vText[5] })
	println(describe { vText[-2] })

	// An index only used on one side of a condition.
	println(firstMatch(vNumbers, 2, 30))
	println(firstMatch(vNumbers, 3, 30))
	println(describe { pick(vNumbers, 9, false) })
	println(describe { pick(vNumbers, 9, true) })
	println(describe { if (vNumbers.size > 5 && vNumbers[5] == 0) "yes" else "no" })
	println(describe { if (vNumbers.size > 2 || vNumbers[5] == 0) "yes" else "no" })

	// Nothing was changed by the failed operations.
	println("${vNumbers[0]} ${vNumbers[1]} ${vNumbers[2]}")
	println("done")
}
