// Lines from the standard input (Input.in), to its end.

fun main() {
	val vFirst = readln()
	println("first: [$vFirst]")
	val vNumbers = readln().split(" ").map { it.toInt() }
	println(vNumbers.sum())
	var vCount = 0
	var vLongest = ""
	while (true) {
		val vLine = readLine() ?: break
		vCount++
		if (vLine.length > vLongest.length) vLongest = vLine
		println("$vCount: [$vLine] ${vLine.length}")
	}
	println("lines: $vCount, longest: [$vLongest]")
	println(readlnOrNull())
	try {
		readln()
	} catch (vError: RuntimeException) {
		println("nothing left")
	}
}
