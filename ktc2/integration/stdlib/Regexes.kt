package stdlib

fun show(inMatch: MatchResult?): String =
	if (inMatch == null) "no match" else "'${inMatch.value}' at ${inMatch.range} ${inMatch.groupValues}"

fun main() {
	// Finding
	val vNumber = Regex("\\d+")
	println(show(vNumber.find("abc 123 def 45")))
	println(show(vNumber.find("abc 123 def 45", 8)))
	println(show(vNumber.find("no digits")))
	println(vNumber.findAll("1 22 333").map { it.value }.toList())
	println(vNumber.containsMatchIn("x9"))
	println(vNumber.matches("12345"))
	println(vNumber.matches("123a"))
	println(vNumber matches "7")
	println(show(vNumber.matchEntire("42")))
	println(show(vNumber.matchAt("ab12", 2)))
	println(vNumber.matchesAt("ab12", 1))

	// Groups, named groups, destructuring
	val vDate = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
	val vMatch = vDate.find("due 2026-11-12, then 2027-01-05")!!
	println(vMatch.groupValues)
	println(vMatch.groups[1]?.value)
	println(vMatch.groups.size)
	val (vYear, vMonth, vDay) = vMatch.destructured
	println("$vDay/$vMonth/$vYear")
	println(vMatch.next()?.value)
	println(vMatch.next()?.next())
	val vNamed = Regex("(?<key>\\w+)=(?<value>[^;]*)")
	for (vPair in vNamed.findAll("a=1;bb=two;c=")) println("${vPair.groups["key"]?.value} -> '${vPair.groups["value"]?.value}'")
	val vOptional = Regex("(a)|(b)").find("b")!!
	println("${vOptional.groups[1]} ${vOptional.groups[2]?.value} ${vOptional.groupValues}")

	// Replacing and splitting
	println(Regex("\\s+").replace("a  b \t c", " "))
	println(vDate.replace("2026-11-12", "$3.$2.$1"))
	println(vNamed.replace("a=1;b=2", "\${value}=\${key}"))
	println(Regex("[aeiou]").replace("regular expression") { it.value.uppercase() })
	println(Regex("o").replaceFirst("foo boo", "0"))
	println(Regex("x*").replace("abc", "-"))
	println(Regex("\\$").replace("cost $5", "\\$\\$"))
	println(Regex(",\\s*").split("a, b,c,   d"))
	println(Regex(",").split("a,b,c,d", 2))
	println(Regex("\\d").split("a1b2c3"))
	println(Regex("").split("abc"))
	println("a1b22c".split(Regex("\\d+")))
	println("2026-11-12".replace(Regex("-"), "/"))
	println("hello world".matches(Regex("h.*d")))
	println("x".toRegex().pattern)

	// Classes, anchors, alternation, quantifiers
	println(Regex("[a-cx-z]+").findAll("abcdxyzw").map { it.value }.toList())
	println(Regex("[^0-9 ]+").findAll("ab 12 cd").map { it.value }.toList())
	println(Regex("[\\w.]+@[\\w.]+").find("mail ann.b@example.org now")?.value)
	println(Regex("^\\w+").find("first second")?.value)
	println(Regex("\\w+$").find("first second")?.value)
	println(Regex("^b", RegexOption.MULTILINE).findAll("a\nb\nb").count())
	println(Regex("\\bcat\\b").findAll("cat concat cat's").count())
	println(Regex("cat|dog|bird").findAll("dog cat cow bird").map { it.value }.toList())
	println(Regex("a{2,3}").findAll("a aa aaa aaaa").map { it.value }.toList())
	println(Regex("<.+>").find("<a><b>")?.value)
	println(Regex("<.+?>").find("<a><b>")?.value)
	println(Regex("(ab)+").find("xababab")?.value)
	println(Regex("(a|ab)(c|bcd)(d*)").find("abcd")?.groupValues)
	println(Regex("(\\w)\\1").findAll("hello bookkeeper").map { it.value }.toList())
	println(Regex("colou?r", RegexOption.IGNORE_CASE).findAll("Color colour COLOUR").count())
	println(Regex("(?i)hello").containsMatchIn("say HeLLo"))
	println(Regex("a.c").matches("a\nc"))
	println(Regex("(?s)a.c").matches("a\nc"))
	println(Regex("\\d+(?= dollars)").find("10 euros, 20 dollars")?.value)
	println(Regex("\\d+(?! dollars)\\b").find("20 dollars 30 euros")?.value)
	println(Regex("(?<=\\$)\\d+").find("cost \$15")?.value)
	println(Regex("(?<!\\$)\\b\\d+").find("\$15 and 16")?.value)
	println(Regex("\\p{Lu}\\p{Ll}+").findAll("Ann met Bob").map { it.value }.toList())
	println(Regex("(a*)*b").matches("aaab"))
	println(Regex("(a|b)*c").find("ababc")?.groupValues)
	println(Regex("\\Qa.b\\E").containsMatchIn("a.b"))
	println(Regex("\\Qa.b\\E").containsMatchIn("axb"))
	println(Regex("\\x41\\u0042\\t").matches("AB\t"))

	// Helpers and errors
	println(Regex.escape("1+1=2"))
	println(Regex.fromLiteral("1+1").containsMatchIn("1+1"))
	println(Regex.escapeReplacement("\$1\\"))
	println(Regex("a+").toString())
	println(Regex("a", setOf(RegexOption.IGNORE_CASE)).options)
	try {
		Regex("(unclosed")
	} catch (vError: IllegalArgumentException) {
		println("bad pattern")
	}
	try {
		Regex("a").find("abc", 9)
	} catch (vError: IndexOutOfBoundsException) {
		println("bad index")
	}
	println("done")
}
