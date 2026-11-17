package stdlib

fun initials(name: String): String {
	var vResult = ""
	for (vPart in name.split(" ")) {
		if (vPart.isNotEmpty()) vResult += vPart[0].uppercaseChar()
	}
	return vResult
}

fun wordCount(text: String): Int = text.trim().split(" ").filter { it.isNotBlank() }.size

fun isPalindrome(text: String): Boolean {
	val vClean = text.lowercase().filter { it.isLetterOrDigit() }
	return vClean == vClean.reversed()
}

fun main() {
	val vText = "  Hello, Kotlin World!  "

	// Trimming, case, length
	println("[" + vText.trim() + "]")
	println("[" + vText.trimStart() + "]")
	println("[" + vText.trimEnd() + "]")
	println(vText.trim().uppercase())
	println(vText.trim().lowercase())
	println(vText.trim().length)
	println("".isEmpty())
	println("  ".isBlank())
	println("x".isNotEmpty())

	// Substrings and search
	val vLine = "key=value=more"
	println(vLine.substring(4))
	println(vLine.substring(0, 3))
	println(vLine.indexOf('='))
	println(vLine.lastIndexOf('='))
	println(vLine.indexOf("value"))
	println(vLine.indexOf("zzz"))
	println(vLine.contains("val"))
	println('k' in vLine)
	println(vLine.startsWith("key"))
	println(vLine.endsWith("more"))
	println(vLine.startsWith("KEY", ignoreCase = true))
	println(vLine.substringBefore('='))
	println(vLine.substringAfter('='))
	println(vLine.substringAfterLast('='))
	println(vLine.substringBeforeLast("="))
	println(vLine.take(3) + vLine.takeLast(4))
	println(vLine.drop(4).dropLast(5))
	println(vLine.first())
	println(vLine.last())
	println(vLine.removePrefix("key=").removeSuffix("=more"))

	// Split, join, replace, repeat
	println(vLine.split("="))
	println("a,b,,c".split(","))
	println("a,b;c".split(",", ";").size)
	println("one two  three".split(" ").joinToString("|"))
	println(vLine.replace("=", " -> "))
	println(vLine.replace('e', 'E'))
	println("aaa".replaceFirst("a", "b"))
	println("ab".repeat(3))
	println("x".padStart(4, '0') + "|" + "x".padEnd(4, '.') + "|")
	println("a\nb\nc".lines())
	println("abc".reversed())
	println(listOf("x", "y").joinToString(prefix = "(", postfix = ")"))

	// Characters
	println("Kotlin".count { it.isUpperCase() })
	println("a1b2c3".filter { it.isDigit() })
	println("hello".map { it.uppercaseChar() })
	println("hello".toCharArray().size)
	println('7'.isDigit())
	println('x'.isLetter())
	println(' '.isWhitespace())
	println('a'.uppercaseChar())
	println('7'.digitToInt() + 1)
	println("abc".any { it == 'b' })
	println("abc".all { it.isLetter() })
	println(initials("ada lovelace byron"))
	println(wordCount("  the quick  brown fox "))
	println(isPalindrome("A man, a plan, a canal: Panama"))
	println(isPalindrome("not one"))

	// Numbers
	println("42".toInt() + 1)
	println("-17".toInt())
	println("123456789012".toLong() * 2)
	println("3.5".toDouble() * 2)
	println("1e3".toDouble())
	println("abc".toIntOrNull())
	println("12".toIntOrNull())
	println("9999999999".toIntOrNull())
	println("2.5x".toDoubleOrNull())
	println("ff".toInt(16))
	println(255.toString(16))
	println(255.toString(2))
	println((-255).toString(16))
	println("true".toBoolean())
	println("7".toByte() + "8".toShort())

	// Comparison
	println("apple" < "banana")
	println("b".compareTo("a"))
	println("Hello".equals("hello", ignoreCase = true))
	println("Hello".equals("hello"))
	println("abc".compareTo("ABD", ignoreCase = true))

	// Building
	val vTable = buildString {
		for (vRow in 1..3) {
			append(vRow)
			append(": ")
			append("#".repeat(vRow))
			appendLine()
		}
	}
	print(vTable)
	val vBuilder = StringBuilder("abc")
	vBuilder.insert(0, "<").append(">").reverse()
	println(vBuilder)
	println("héllo wörld".uppercase())
	println("ÀÉ".lowercase())
	println("héllo".substring(1, 3))
	println("naïve café".split(" ").map { it.length })
}
