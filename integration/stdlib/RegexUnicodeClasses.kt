/*
The flag U of a regex (Unicode classes for \w, \d, \s, \b and the POSIX names), and scripts by their four-letter
codes: what the JVM has. The engine is Kotlin/Native's (Harmony's), which has neither: it refuses those patterns,
and RegexUnicodeClasses.expected is Kotlin/Native's output.
*/
fun show(inPattern: String, inText: String) {
	val vFound = try {
		Regex(inPattern).findAll(inText).map { it.value }.toList().toString()
	} catch (vError: IllegalArgumentException) {
		"refused"
	}
	println("$inPattern on $inText: $vFound")
}

fun main() {
	val text = "café_1 Ωμέγα ٣٤ x\u00A0y\u2003z \u1C00 \u0300a ½ Ⅻ ẞ ǅ \u200D"
	for (vPattern in listOf("\\w+", "\\d+", "\\s+", "\\b\\w", "\\p{Alpha}+", "\\p{Lower}+", "\\p{Upper}+", "\\p{Alnum}+", "\\p{Punct}+", "\\p{Space}+", "\\p{Blank}+", "\\p{XDigit}+", "\\p{Graph}+", "\\p{Print}+", "\\p{Cntrl}+")) {
		show(vPattern, text)
		show("(?U)$vPattern", text)
	}
	show("(?U:\\w+)-\\w+", "été-été")
	show("\\p{IsAlphabetic}+", "a\u0345b\u2160")
	show("\\p{IsWhite_Space}+", "a\u001Cb\u0085c\u2028d")
	show("\\p{IsLatin}+", "abc Ωμέγα")
	show("\\p{sc=Latn}+", "abc Ωμέγα")
	show("\\p{script=Grek}+", "abc Ωμέγα")
	show("\\p{IsCyrl}+", "abc Привет")
	show("\\p{sc=Hani}+", "漢字 kana かな")
	show("\\P{sc=Latn}+", "abcΩμ")
	show("(?Ui)ÉTÉ", "été")
}
