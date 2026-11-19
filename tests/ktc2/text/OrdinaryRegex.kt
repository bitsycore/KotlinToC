// `Regex` as ordinary code uses it: validation, extraction with groups and named groups,
// destructuring, replacement with references and with a function, splitting, every match with
// its range, options, anchors in several lines, lookaround, back references, lazy and
// possessive quantifiers, classes of Unicode, escaping.

package ktc2test.text.ordinaryregex

import kotlin.test.Test

@Test
fun main() {
	val email = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
	for (candidate in listOf("ann@example.com", "bad@", "x.y+z@sub-domain.co.uk", "@no.pe")) {
		println("$candidate ${email.matches(candidate)}")
	}

	val date = Regex("""(\d{4})-(\d{2})-(\d{2})""")
	val match = date.find("due 2024-03-15 and 2025-12-01")!!
	println(match.value + " " + match.range + " " + match.groupValues)
	val (year, month, day) = match.destructured
	println("$day/$month/$year")
	println(match.next()?.value)
	println(date.findAll("2020-01-02 2021-02-03 x 2022-03-04").map { it.groupValues[1] }.toList())
	println(date.replace("on 2024-03-15", "$3.$2.$1"))
	println(date.replace("2024-03-15, 2023-01-09") { "${it.groupValues[1].toInt() + 1}" })

	val named = Regex("""(?<key>\w+)=(?<value>[^;]*)""")
	val settings = named.findAll("a=1;bb=two;c=;d=4 4").associate { it.groups["key"]!!.value to it.groups["value"]!!.value }
	println(settings)
	println(named.find("x=y")!!.groups["value"]?.range)
	println(named.replace("k=v;n=m", "\${value}:\${key}"))

	println("a1b22c333".split(Regex("""\d+""")))
	println("a, b;c ,d".split(Regex("""\s*[,;]\s*""")))
	println("one  two   three".split(Regex("\\s+"), limit = 2))
	println(Regex(",").split("a,,b,"))
	println("CamelCaseString".split(Regex("(?=[A-Z])")).filter { it.isNotEmpty() })

	val words = Regex("""\b\w+\b""")
	println(words.findAll("The quick, brown fox!").map { "${it.value}@${it.range.first}" }.toList())
	println(words.findAll("abc def", startIndex = 2).map { it.value }.toList())
	println(words.find("  hello", 3)?.value)
	println(Regex("o").findAll("foo boo").count())

	val ignore = Regex("hello", RegexOption.IGNORE_CASE)
	println(ignore.containsMatchIn("Say HeLLo"))
	println(ignore.replace("hello HELLO Hello", "hi"))
	val lines = Regex("^(\\w+):", RegexOption.MULTILINE)
	println(lines.findAll("name: a\nage: 3\n  skip: x\nend:").map { it.groupValues[1] }.toList())
	println(Regex("a.b", RegexOption.DOT_MATCHES_ALL).matches("a\nb"))
	println(Regex("a.b").matches("a\nb"))
	println(Regex("""(\d+) # number""", RegexOption.COMMENTS).find("x 42")?.groupValues)

	println(Regex("""\d+(?=px)""").findAll("10px 20em 30px").map { it.value }.toList())
	println(Regex("""(?<!\$)\b\d+""").findAll("$5 7 $9 11").map { it.value }.toList())
	println(Regex("""(\w)\1""").findAll("aabbcd eef").map { it.value }.toList())
	println(Regex("""<.+?>""").findAll("<a><b></b>").map { it.value }.toList())
	println(Regex("""<.+>""").find("<a><b></b>")?.value)
	println(Regex("""a++b""").matches("aaab"))
	println(Regex("""(a|ab)(c|bcd)(d*)""").find("abcd")?.groupValues)
	println(Regex("""x*""").replace("abc", "-"))
	println(Regex("""(\d)(\d)?""").find("5")?.groups?.map { it?.value })

	println(Regex("""\p{L}+""").findAll("héllo wörld 123 日本").map { it.value }.toList())
	println(Regex("""\p{Lu}""").findAll("aBcDé").map { it.value }.toList())
	println(Regex("""[^\x00-\x7F]""").findAll("naïve café").count())

	val literal = Regex.escape("1+1=2?")
	println(Regex(literal).matches("1+1=2?"))
	println(Regex.fromLiteral("a.b").matches("axb"))
	println("cost: $5".replace(Regex("\\$(\\d)"), Regex.escapeReplacement("$") + "$1.00"))
	println(Regex("[aeiou]").replaceFirst("banana", "_"))
	println(Regex("(\\w+) (\\w+)").matchEntire("hello world")?.groupValues)
	println(Regex("\\w+").matchEntire("hello world"))
	println(Regex("abc").matchesAt("xxabc", 2))
	println(Regex("abc").matchAt("xxabcx", 2)?.range)
	println(Regex("[0-9]+").pattern)
	println(Regex("a", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).options.sortedBy { it.name })
	println("2 apples and 10 pears".replace(Regex("\\d+")) { (it.value.toInt() * 2).toString() })
	println(runCatching { Regex("(unclosed") }.isFailure)
	println("a-b_c d".replace(Regex("[-_ ]"), "").uppercase())
	println(Regex("(?i)straße").containsMatchIn("STRASSE"))
	println(Regex("^\\s*$").matches("   "))
	println(Regex("(a)|(b)").find("b")?.groups?.get(1))
}
