package stdlib

// Searching in strings: all ASCII (done on bytes by the runtime) and not (done character by
// character), with the indices at and beyond both ends.

fun probe(inText: String, inWanted: String) {
	val vStarts = listOf(-3, 0, 1, inText.length - 1, inText.length, inText.length + 2)
	println(vStarts.joinToString(" ") { inText.indexOf(inWanted, it).toString() } + " | " +
		vStarts.joinToString(" ") { inText.lastIndexOf(inWanted, it).toString() })
}

fun probeChar(inText: String, inWanted: Char) {
	val vStarts = listOf(-3, 0, 2, inText.length - 1, inText.length, inText.length + 2)
	println(vStarts.joinToString(" ") { inText.indexOf(inWanted, it).toString() } + " | " +
		vStarts.joinToString(" ") { inText.lastIndexOf(inWanted, it).toString() })
}

fun main() {
	val vPlain = "abracadabra abracadabra"
	val vAccents = "été à la plage, l'été"
	val vBeyond = "a😀b😀c"
	for (vText in listOf(vPlain, vAccents, vBeyond, "")) {
		for (vWanted in listOf("abra", "a", "", "été", "😀", "zz", "cadabra abracadabra!", vText)) probe(vText, vWanted)
		for (vWanted in listOf('a', 'é', 'z', ' ', '\uD83D')) probeChar(vText, vWanted)
	}
	println(vPlain.contains("cad") && !vPlain.contains("dac") && vAccents.contains("plage") && "".contains(""))
	println(vPlain.startsWith("abra") && vPlain.startsWith("cad", 4) && !vPlain.startsWith("abra", 1) && vPlain.endsWith("dabra"))
	println(vAccents.startsWith("été") && vAccents.endsWith("l'été") && !vAccents.endsWith("ete") && vPlain.startsWith("") && "".endsWith(""))
	println(vPlain.startsWith("ABRA", ignoreCase = true) && !vPlain.startsWith("ABRA") && vAccents.startsWith("ÉTÉ", ignoreCase = true))
	println(vPlain.regionMatches(4, "xcadx", 1, 3) && !vPlain.regionMatches(-1, "a", 0, 1) && !vPlain.regionMatches(20, "abracadabra", 0, 11))
	println(vPlain.replace("abra", "[]") + " " + vAccents.replace("été", "hiver") + " " + vPlain.replace("", "-").length)
	println(vPlain.split("a").size.toString() + " " + vPlain.split(" ") + " " + vAccents.split("é"))
	println(vPlain.substringAfter("cad") + "|" + vPlain.substringBeforeLast("abra") + "|" + vAccents.substringAfterLast("'"))
	println(vPlain.indexOf("ABRA", 1, ignoreCase = true).toString() + " " + vPlain.lastIndexOf("ABRA", ignoreCase = true) + " " + vPlain.indexOfAny(charArrayOf('c', 'd')))
	// A loop over a string that is not ASCII, forwards, backwards and jumping.
	var vCode = 0
	for (vI in vAccents.indices) vCode += vAccents[vI].code * (vI + 1)
	for (vI in vAccents.indices.reversed()) vCode -= vAccents[vI].code
	for (vI in 0 until vBeyond.length step 2) vCode += vBeyond[vI].code
	println(vCode.toString() + " " + vBeyond.length + " " + vBeyond[1].code + " " + vBeyond[2].code + " " + vBeyond.substring(3, 6))
	// The same position asked of different strings in turn.
	val vOthers = (0 until 4).map { "é".repeat(it + 1) + it }
	var vSeen = ""
	for (vRound in 0 until 3) for (vOther in vOthers) vSeen += vOther[vOther.length - 1]
	println(vSeen)
}
