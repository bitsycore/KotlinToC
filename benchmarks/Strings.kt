// Benchmark: building, cutting and reading strings.

/** A checksum over the characters of [inText]. */
fun checksum(inText: String): Int {
	var vHash = 0
	for (vI in 0 until inText.length) vHash = vHash * 31 + inText[vI].code
	return vHash
}

fun main() {
	var vHash = 0
	for (vI in 0 until 1000000) {
		val vLine = "item $vI of ${vI % 17}: ${vI * 3}"
		vHash += checksum(vLine)
	}
	println(vHash)

	val vBuilder = StringBuilder()
	for (vI in 0 until 100000) {
		vBuilder.append(vI % 10)
		vBuilder.append(if (vI % 5 == 0) ' ' else 'x')
	}
	val vText = vBuilder.toString()
	println(vText.length)

	var vWords = 0
	var vLetters = 0
	for (vRound in 0 until 60) {
		for (vWord in vText.split(' ')) {
			vWords++
			vLetters += vWord.length
		}
	}
	println("$vWords $vLetters")

	var vFound = 0
	for (vI in 0 until 2000) {
		if (vText.contains("9x0 ")) vFound++
		vFound += vText.indexOf("x5x", vI)
	}
	println(vFound)

	val vAccents = "été à la plage, où l'eau est froide. ".repeat(200)
	var vCode = 0
	for (vRound in 0 until 4) {
		for (vI in vAccents.indices) vCode += vAccents[vI].code
	}
	println(vCode)
	println(vAccents.uppercase().substring(0, 20))
}
