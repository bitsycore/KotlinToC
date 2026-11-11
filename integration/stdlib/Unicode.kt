// Characters beyond ASCII: what they are, their case, their value as digits.

fun describe(inChar: Char): String {
	val vWhat = StringBuilder()
	if (inChar.isLetter()) vWhat.append('L')
	if (inChar.isDigit()) vWhat.append('D')
	if (inChar.isUpperCase()) vWhat.append('U')
	if (inChar.isLowerCase()) vWhat.append('l')
	if (inChar.isTitleCase()) vWhat.append('T')
	if (inChar.isWhitespace()) vWhat.append('W')
	if (!inChar.isDefined()) vWhat.append('?')
	return inChar.code.toString(16) + ":" + vWhat + ":" + inChar.category.code
}

fun main() {
	val vSamples = "aZ5 _éÉßÿŒœǅΩωςЖжאب٣३แ中あア가€±∑  　͸￿ẞⅠⒶªⅷ"
	println(vSamples.map { describe(it) }.joinToString(" "))

	// Case of single characters.
	println("éÉßÿŒœǅǆΩωςЖжıİſ".map { "" + it.uppercaseChar() + it.lowercaseChar() + it.titlecaseChar() }.joinToString(" "))
	println('ß'.uppercase() + " " + 'ŉ'.uppercase() + " " + 'ﬁ'.uppercase() + " " + 'İ'.lowercase().length + " " + 'ǆ'.titlecase() + " " + 'ß'.titlecase())

	// Case of strings.
	println("Straße in Köln, Ærøskøbing, ǅungla".uppercase())
	println("STRASSE IN KÖLN, ÆRØSKØBING".lowercase())
	println("ΟΔΥΣΣΕΥΣ ΚΑΙ ΣΕΙΡΗΝΕΣ Σ".lowercase())
	println("Привет, Мир! ĞÜŞİÖÇ ğüşiöç".uppercase() + " | " + "Привет, Мир! ĞÜŞİÖÇ".lowercase().length)
	println("élan vital".replaceFirstChar { it.titlecase() } + " " + "ÉCOLE".lowercase().replaceFirstChar { it.uppercase() })
	println("Ünïcödé".equals("üNÏCÖDÉ", ignoreCase = true).toString() + " " + "ΣΊΣΥΦΟΣ".equals("σίσυφος", ignoreCase = true) + " " + "straße".compareTo("STRASSE", ignoreCase = true).coerceIn(-1, 1))

	// Digits of other scripts.
	println("٣३๔７".map { it.digitToInt() }.joinToString("") + " " + "٤٢".toInt() + " " + "１２３".toLong() + " " + 'Ｆ'.digitToIntOrNull(16) + " " + 'x'.digitToIntOrNull())

	// Classification at work in the library.
	println("    déjà vu　 ".trim().length.toString() + " " + "été 2024 — año ٢٠٢٤".filter { it.isLetterOrDigit() })
	println("Été Über ça Öl".split(" ").filter { it[0].isUpperCase() }.size.toString() + " " + "naïve café".count { it.isLetter() })
	val vCounts = HashMap<CharCategory, Int>()
	for (vCode in 0..0xFFFF) vCounts[vCode.toChar().category] = (vCounts[vCode.toChar().category] ?: 0) + 1
	println(CharCategory.entries.joinToString(" ") { it.code + (vCounts[it] ?: 0) })
	var vUpper = 0
	var vLower = 0
	var vChanged = 0
	for (vCode in 0..0xFFFF) {
		val vChar = vCode.toChar()
		if (vChar.isUpperCase()) vUpper++
		if (vChar.isLowerCase()) vLower++
		if (vChar.uppercaseChar() != vChar || vChar.lowercaseChar() != vChar || vChar.titlecaseChar() != vChar.uppercaseChar()) vChanged++
	}
	println("$vUpper $vLower $vChanged")
}
