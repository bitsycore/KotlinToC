// An assignment whose right-hand side fails leaves the variable, the field, the element or
// the top-level property as it was.

class Box(var value: Int, var name: String)

var gValue = 7
var gName = "global"

fun failing(inFail: Boolean): Int {
	if (inFail) throw IllegalStateException("no value")
	return 100
}

fun failingName(inFail: Boolean): String {
	if (inFail) throw IllegalStateException("no name")
	return "new name"
}

fun main() {
	val vNumbers = intArrayOf(1, 2, 3)
	val vNames = arrayOf("a", "b")
	val vBox = Box(5, "box")
	var vLocal = 9
	var vText = "local"

	// A call that throws.
	try { vLocal = failing(true) } catch (vError: IllegalStateException) { println("local " + vLocal) }
	try { vText = failingName(true) } catch (vError: IllegalStateException) { println("text " + vText) }
	try { vBox.value = failing(true) } catch (vError: IllegalStateException) { println("field " + vBox.value) }
	try { vBox.name = failingName(true) } catch (vError: IllegalStateException) { println("field " + vBox.name) }
	try { vNumbers[1] = failing(true) } catch (vError: IllegalStateException) { println("element " + vNumbers[1]) }
	try { vNames[1] = failingName(true) } catch (vError: IllegalStateException) { println("element " + vNames[1]) }
	try { gValue = failing(true) } catch (vError: IllegalStateException) { println("global " + gValue) }
	try { gName = failingName(true) } catch (vError: IllegalStateException) { println("global " + gName) }

	// A check of the language inside a condition.
	val vPick = vNumbers.size
	try { vLocal = if (vPick > 0) vNumbers[vPick] else 0 } catch (vError: IndexOutOfBoundsException) { println("local " + vLocal) }
	try { vBox.value = if (vPick > 0) vNumbers[vPick + 1] else 0 } catch (vError: IndexOutOfBoundsException) { println("field " + vBox.value) }
	try { vNumbers[0] = if (vPick > 0) 10 / (vPick - 3) else 0 } catch (vError: ArithmeticException) { println("element " + vNumbers[0]) }
	try { gValue = if (vPick > 0) vNumbers[-1] else 0 } catch (vError: IndexOutOfBoundsException) { println("global " + gValue) }
	try { vBox.value += vNumbers[vPick] } catch (vError: IndexOutOfBoundsException) { println("field " + vBox.value) }

	// The same assignments when nothing fails.
	vLocal = failing(false)
	vText = failingName(false)
	vBox.value = failing(false) + 1
	vBox.name = failingName(false)
	vNumbers[1] = failing(false) + 2
	vNames[1] = failingName(false)
	gValue = if (vPick > 0) vNumbers[vPick - 1] else 0
	gName = failingName(false)
	println("$vLocal $vText ${vBox.value} ${vBox.name} ${vNumbers[1]} ${vNames[1]} $gValue $gName")
}
