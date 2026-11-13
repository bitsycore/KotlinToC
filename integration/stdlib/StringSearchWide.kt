// Searching and comparing text that is not all ASCII: accents (two bytes), other scripts
// (three), characters beyond U+FFFF (a surrogate pair, four bytes), and indices inside a pair.

fun search(inText: String, inWanted: String) {
	val vAll = ArrayList<Int>()
	var vAt = inText.indexOf(inWanted)
	while (vAt >= 0 && vAll.size < 20) {
		vAll += vAt
		vAt = inText.indexOf(inWanted, vAt + 1)
	}
	val vBack = ArrayList<Int>()
	vAt = inText.lastIndexOf(inWanted)
	while (vAt >= 0 && vBack.size < 20) {
		vBack += vAt
		vAt = if (vAt == 0) -1 else inText.lastIndexOf(inWanted, vAt - 1)
	}
	println("${inWanted.length} units in ${inText.length}: $vAll back $vBack contains ${inText.contains(inWanted)} starts ${inText.startsWith(inWanted)} ends ${inText.endsWith(inWanted)}")
}

fun searchChar(inText: String, inWanted: Char) {
	val vAll = ArrayList<Int>()
	var vAt = inText.indexOf(inWanted)
	while (vAt >= 0) {
		vAll += vAt
		vAt = inText.indexOf(inWanted, vAt + 1)
	}
	println("char ${inWanted.code}: $vAll last ${inText.lastIndexOf(inWanted)} before 3: ${inText.lastIndexOf(inWanted, 3)}")
}

fun main() {
	val vText = "héllo wörld, こんにちは 😀 and 😀😁 héllo"
	for (vWanted in listOf("héllo", "é", "llo", "wörld", "こん", "は", "😀", "😁", "😀😁", " ", "", "x", "héllo wörld, こんにちは 😀 and 😀😁 héllo", "héllo wörld, こんにちは 😀 and 😀😁 héllo!")) {
		search(vText, vWanted)
	}
	// Plain text searched for what it cannot hold, and the other way round.
	search("plain text", "é")
	search("é", "plain text")
	search("ééé", "éé")
	search("😀😀😀", "😀😀")

	// From every index, those inside a pair too.
	val vPairs = "a😀b😀c"
	for (vFrom in -1..vPairs.length + 1) {
		println("from $vFrom: ${vPairs.indexOf("😀", vFrom)} ${vPairs.lastIndexOf("😀", vFrom)} ${vPairs.indexOf("", vFrom)} ${vPairs.lastIndexOf("", vFrom)} ${vPairs.indexOf('b', vFrom)} ${vPairs.lastIndexOf('c', vFrom)}")
	}

	// Halves of a pair, as characters: found inside a pair.
	searchChar(vPairs, "😀"[0])
	searchChar(vPairs, "😀"[1])
	searchChar(vText, 'é')
	searchChar(vText, 'に')
	searchChar(vText, 'l')
	searchChar("plain", 'é')

	// Regions.
	println(vText.regionMatches(0, "xhé", 1, 2))
	println(vText.regionMatches(13, "こんにちは", 0, 5))
	println(vText.regionMatches(13, "こんにちわ", 0, 5))
	println(vPairs.regionMatches(1, "😀", 0, 2))
	println(vPairs.regionMatches(2, "😀", 1, 1))
	println(vPairs.regionMatches(1, "😀", 0, 1))
	println(vPairs.regionMatches(2, "😁", 1, 1))
	println(vPairs.regionMatches(1, "😁", 0, 1))
	println(vText.regionMatches(1, "É", 0, 1, ignoreCase = true))

	// What is built on them.
	println(vText.split("😀"))
	println(vText.split(" ", "ö"))
	println(vText.replace("héllo", "bye").replace("😀", "<>").replace('ö', 'o'))
	println(vText.substringAfter("こん").substringBefore("😁"))
	println("été, hiver, été".split(", ").map { it.length })
	println(vText.removePrefix("hé").removeSuffix("llo"))
	println(vText.indexOfAny(charArrayOf("😀"[0], 'ö')))
	println("çà et là".commonPrefixWith("çà ou là") + "|" + "ici là".commonSuffixWith("et là"))
	// Two strings read side by side.
	val vOther = "héllo wörld, こんばんは"
	var vSame = 0
	while (vSame < vOther.length && vText[vSame] == vOther[vSame]) vSame++
	println("same up to $vSame")
}
