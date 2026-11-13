// Halves of a surrogate pair on their own: a string holds any sequence of characters. What is
// printed are numbers and lengths: a half on its own has no form outside the program.

fun codes(inText: String): String = inText.map { it.code.toString(16) }.joinToString(" ") + " (" + inText.length + ")"

fun main() {
	val vFace = "😀"
	val vHigh = vFace[0]
	val vLow = vFace[1]
	val vOnlyHigh = vHigh.toString()
	val vOnlyLow = vLow.toString()
	println(codes(vOnlyHigh))
	println(codes(vOnlyLow))
	println(codes("a" + vHigh + "b" + vLow + "c"))
	println(codes("\uD83D"))
	println(codes("x\uDE00y\uD83Dz"))

	// Two halves that meet are the character.
	val vJoined = vOnlyHigh + vOnlyLow
	println("${codes(vJoined)} ${vJoined == vFace} ${vJoined.hashCode() == vFace.hashCode()} ${vJoined.compareTo(vFace)}")
	println(vJoined)
	val vBuilder = StringBuilder()
	vBuilder.append(vHigh).append(vLow).append(vLow).append(vHigh)
	println(codes(vBuilder.toString()))
	println("${"$vHigh$vLow" == vFace} ${("ab" + vHigh) + (vLow + "cd") == "ab😀cd"} ${String(charArrayOf(vHigh, vLow)) == vFace}")
	val vTemplate = "<$vOnlyHigh$vOnlyLow>"
	println("$vTemplate ${vTemplate.length}")
	// The wrong way round they stay two halves.
	println(codes(vOnlyLow + vOnlyHigh))

	// A part of a string that cuts a pair.
	val vText = "a😀b😁c"
	println(codes(vText.substring(0, 2)))
	println(codes(vText.substring(2)))
	println(codes(vText.substring(2, 5)))
	println(codes(vText.substring(1, 3)))
	println(vText.substring(0, 2) + vText.substring(2) == vText)
	println(codes(vText.take(2) + vText.drop(5)))
	println(codes(vText.reversed()))
	println(codes(StringBuilder("x" + vOnlyLow + vOnlyHigh + vFace + vOnlyHigh).reverse().toString()))
	println(codes(vText.filter { it.isLowSurrogate() }))
	println(vText.map { it.isSurrogate() })

	// Compared, hashed, searched.
	println("${vOnlyHigh == vOnlyLow} ${vOnlyHigh == "\uD83D"} ${vOnlyHigh < vOnlyLow} ${vOnlyHigh.compareTo("z")} ${vOnlyHigh.hashCode()} ${vOnlyLow.hashCode()}")
	println("${vText.indexOf(vHigh)} ${vText.indexOf(vLow)} ${vText.lastIndexOf(vHigh)} ${vText.indexOf(vOnlyHigh)} ${vText.indexOf(vOnlyLow)} ${vText.lastIndexOf(vOnlyLow)}")
	println("${vText.indexOf(vOnlyLow + "b")} ${vText.indexOf("a" + vOnlyHigh)} ${vText.contains(vOnlyHigh + "b")} ${vText.startsWith("a" + vOnlyHigh)} ${vText.endsWith(vFace[1] + "b😁c")}")
	val vHalves = "x" + vOnlyHigh + "y" + vOnlyLow + "z" + vOnlyHigh
	println("${vHalves.indexOf(vOnlyHigh)} ${vHalves.lastIndexOf(vOnlyHigh)} ${vHalves.indexOf(vOnlyLow)} ${vHalves.indexOf(vOnlyHigh + "y")} ${vHalves.indexOf(vFace)} ${vHalves.split(vOnlyHigh).map { it.length }} ${codes(vHalves.replace(vOnlyLow, "-"))}")
	val vKeys = hashMapOf(vOnlyHigh to 1, vOnlyLow to 2, vFace to 3)
	println("${vKeys[vOnlyHigh]} ${vKeys[vOnlyLow]} ${vKeys[vOnlyHigh + vOnlyLow]} ${vKeys.size}")

	// Outside the program a half is a question mark.
	println("[$vOnlyHigh] [$vOnlyLow] [${vText.substring(0, 2)}]")
	println(vHigh)
	println(vOnlyHigh.encodeToByteArray().toList())
	println(("a" + vOnlyLow + "é").encodeToByteArray().toList())
	println(vText.toCharArray().concatToString() == vText)
	println(vText.uppercase() == vText.uppercase().lowercase().uppercase())
}
