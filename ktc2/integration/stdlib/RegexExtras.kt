// Regex: atomic groups, possessive quantifiers, class intersection, flags inside the pattern,
// the classes of Unicode, and case without regard to it beyond ASCII.

fun all(inPattern: String, inText: String): String = Regex(inPattern).findAll(inText).joinToString("|") { it.value }

fun all(inRegex: Regex, inText: String): String = inRegex.findAll(inText).joinToString("|") { it.value }

fun refused(inPattern: String): Boolean = try {
	Regex(inPattern)
	false
} catch (vError: IllegalArgumentException) {
	true
}

fun main() {
	// Atomic groups and possessive quantifiers: what was taken is not given back.
	println(Regex("a(?>bc|b)c").containsMatchIn("abc").toString() + " " + Regex("a(?:bc|b)c").containsMatchIn("abc") + " " + Regex("a(?>bc|b)c").find("abcc")?.value)
	println(Regex("\\d++\\d").containsMatchIn("12345").toString() + " " + Regex("\\d+\\d").find("12345")?.value + " " + Regex("\\w*+!").find("hello!")?.value)
	println(all("\"[^\"]*+\"", "say \"one\" and \"two\" and \"three") + " " + all("x?+x", "x xx") + " " + all("(?>a+)b", "aaab ab b"))
	println(Regex("(?>(a)|b)\\1").find("aa")?.groupValues.toString() + " " + all("[a-c]{2,3}+c", "abcc abc") + " " + all("(ab)*+a?", "ababa"))

	// Intersection of classes.
	println(all("[a-z&&[^aeiou]]+", "education is everything") + " " + all("[\\w&&[^\\d_]]+", "ab_12cd3") + " " + all("[a-z&&d-m&&[^f]]+", "abcdefghijklmnop"))
	println(all("[^a-m&&[^x-z]]+", "abcnopxyzq") + " " + all("[\\p{L}&&[^\\p{Lu}]]+", "HeLLo WoRLD éÉ"))

	// Flags from a place on, for a group, switched off again, and comments.
	println(all("a(?i)b", "ab aB AB Ab") + " " + all("(?i:a)b", "ab aB AB Ab") + " " + all("(?i)a(?-i)b", "ab aB AB Ab") + " " + all("(a(?i)b)c", "abc aBc aBC"))
	println(all("(?s)a.b", "a\nb a-b") + " " + all("a.b", "a\nb a-b") + " " + all("(?m)^\\w+$", "one\ntwo three\nfour") + " " + all("(?s:.)\\d", "\n1 a2"))
	println(all("(?x) \\d+  # a number\n [a-z]+  # then letters", "12ab 3 4cd") + " " + all("(?x)a\\ b \\ c [x y]", "a b cx a b c ") + " " + all("(?ix) hello \\s world", "HeLLo WoRLD"))
	println(all("(?i)(a|b(?-i)c)d", "ad AD bcd BCD bCd Bcd") + " " + all("x(?i:y|(?-i:z))", "xy xY xz xZ"))

	// Case beyond ASCII: (?i) alone does not know it, the option and (?iu) do.
	println(all("(?i)straße|école", "STRASSE Straße ÉCOLE École") + " " + all("(?iu)école", "ÉCOLE École ecole"))
	println(all(Regex("école|ωμέγα|жук", RegexOption.IGNORE_CASE), "ÉCOLE Ωμέγα ЖУК жук") + " " + all(Regex("[а-я]+", RegexOption.IGNORE_CASE), "Привет МИР"))
	println(all(Regex("(é)\\1", RegexOption.IGNORE_CASE), "éÉ ée") + " " + Regex("ÅNGSTRÖM", RegexOption.IGNORE_CASE).matches("ångström") + " " + all("(?i)[a-f]+", "DEADbeef ÀÉ"))

	// The classes of Unicode.
	val vText = "Ünï-cödé 123 ٤٥ ǅ Σίσυφος, «мир»! $ + ⅷ a_b c\t"
	println(all("\\p{L}+", vText))
	println(all("\\p{Lu}", vText) + " / " + all("\\p{Ll}+", vText) + " / " + all("\\p{Lt}", vText) + " / " + all("\\pN+", vText) + " / " + all("\\p{Nd}+", vText) + " / " + all("\\p{Nl}", vText))
	println(all("\\p{P}", vText) + " / " + all("\\p{Pi}|\\p{Pf}", vText) + " / " + all("\\p{S}", vText) + " / " + all("\\p{Sc}", vText) + " / " + all("\\p{Zs}", vText).length + " / " + all("\\p{Cc}", vText).length)
	println(all("\\p{IsAlphabetic}+", vText) + " / " + all("\\p{IsUppercase}+", vText) + " / " + all("\\p{IsLowercase}+", vText) + " / " + all("\\p{IsLetter}+", "aé1_"))
	println(all("\\p{Lower}+", vText) + " / " + all("\\p{Upper}+", vText) + " / " + all("\\p{Alpha}+", vText) + " / " + all("\\p{Alnum}+", vText) + " / " + all("\\p{Punct}+", vText) + " / " + all("\\p{XDigit}+", "0xCAFE g"))
	println(all("\\p{javaLowerCase}+", vText) + " / " + all("\\p{javaUpperCase}+", vText) + " / " + all("\\p{javaLetterOrDigit}+", vText) + " / " + all("\\p{javaWhitespace}", vText).length + " / " + all("\\p{IsWhite_Space}", vText).length)
	println(all("\\P{L}+", "ab12 cd!") + " / " + all("[^\\p{L}\\p{N}]+", "ab12 cd!e") + " / " + all("\\p{general_category=Lu}+|\\p{gc=Nd}+", "ABc12") + " / " + all("\\p{LC}+", "abǅ中x") + " / " + all("\\p{IsLu}\\p{IsLl}+", "Hello World"))

	// What a word is for \b and \w: ASCII letters, digits and the underscore.
	println("été déjà_vu x".split(Regex("\\b")).joinToString("|") + " / " + all("\\w+", "été déjà_vu x") + " / " + all("\\b\\p{L}+\\b", "naïve café ok"))

	// Searching on through a long text, and replacing in it.
	val vLong = StringBuilder()
	for (vIndex in 0 until 2000) vLong.append("item").append(vIndex).append(if (vIndex % 3 == 0) "; " else ", ")
	val vWords = Regex("[a-z]+(\\d+)").findAll(vLong).sumOf { it.groupValues[1].toInt() }
	println(vWords.toString() + " " + Regex("\\d+").replace(vLong, "#").length + " " + Regex("[;,] ").split(vLong).size + " " + Regex("item(\\d)\\1\\1\\b").findAll(vLong).count())

	// Scripts and blocks.
	val vMixed = "Latin Ελληνικά Кириллица עברית 中文 かな カナ → ∑ 123"
	println(all("\\p{IsLatin}+", vMixed) + " / " + all("\\p{IsGreek}+", vMixed) + " / " + all("\\p{script=Cyrillic}+", vMixed) + " / " + all("\\p{sc=Hebrew}+", vMixed) + " / " + all("\\p{IsHan}+|\\p{IsHiragana}+|\\p{IsKatakana}+", vMixed))
	println(all("\\p{InGreek}+", vMixed) + " / " + all("\\p{InArrows}|\\p{block=MathematicalOperators}", vMixed) + " / " + all("\\p{InBasicLatin}+", "aé b") + " / " + all("\\P{IsLatin}+", "ab цд ef") + " / " + all("\\p{blk=Basic_Latin}+", "xyz é") + " / " + all("\\p{IsCommon}+", "a, b!"))

	// Lines that end with a line feed only.
	println(all("(?d).+", "a\rb\nc").replace("\r", "<r>") + " / " + all(".+", "a\rb\nc") + " / " + all("(?dm)^\\w$", "a\rb\nc\nd"))

	// Refused, here as on the JVM.
	println(refused("\\p{Nope}").toString() + " " + refused("\\p{IsNope}") + " " + refused("\\p{InNowhere}") + " " + refused("a**") + " " + refused("(?>a") + " " + refused("[a-") + " " + refused("x{2,1}") + " " + refused("(?q)a"))
}
