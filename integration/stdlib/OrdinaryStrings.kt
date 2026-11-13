// What ordinary code does with text.

fun main() {
	val vText = "  The quick brown fox, the lazy dog.  "
	val vTrimmed = vText.trim()
	println("[${vText.trimStart()}] [${vText.trimEnd()}] [$vTrimmed] ${vTrimmed.length} ${vText.isBlank()} ${"".isEmpty()} ${" ".isBlank()} ${"x".isNotBlank()} ${vTrimmed.trim('T', '.')} ${"xxhixx".trimStart('x')} ${"--a--".trim { it == '-' }}")
	println("${vTrimmed.uppercase()} ${vTrimmed.lowercase()} ${vTrimmed.replaceFirstChar { it.lowercase() }} ${"word".replaceFirstChar { it.titlecase() }} ${vTrimmed.reversed()} ${"ab".repeat(3)} ${"x".padStart(4, '0')} ${"x".padEnd(4, '.')}| ${"long text".padStart(3)}")
	println("${vTrimmed.indexOf("the")} ${vTrimmed.indexOf("the", ignoreCase = true)} ${vTrimmed.lastIndexOf('o')} ${vTrimmed.indexOf('z')} ${vTrimmed.contains("FOX", ignoreCase = true)} ${"fox" in vTrimmed} ${vTrimmed.startsWith("The")} ${vTrimmed.endsWith("dog.")} ${vTrimmed.startsWith("quick", 4)} ${vTrimmed.indexOfAny(charArrayOf('q', 'z'))} ${vTrimmed.indexOfFirst { it == ',' }} ${vTrimmed.count { it == 'o' }} ${vTrimmed.first()} ${vTrimmed.last()} ${vTrimmed[4]}")
	println("${vTrimmed.substring(4, 9)} ${vTrimmed.substring(31)} ${vTrimmed.substringBefore(',')} | ${vTrimmed.substringAfter(", ")} | ${vTrimmed.substringBeforeLast(' ')} | ${vTrimmed.substringAfterLast(' ')} | ${vTrimmed.substringAfter("none", "default")} ${vTrimmed.take(3)} ${vTrimmed.takeLast(4)} ${vTrimmed.drop(30)} ${vTrimmed.dropLast(30)} ${vTrimmed.slice(4..8)} ${vTrimmed.removePrefix("The ")} ${"a.txt".removeSuffix(".txt")} ${"[x]".removeSurrounding("[", "]")}")
	println("${vTrimmed.split(" ")} ${vTrimmed.split(", ", ". ")} ${vTrimmed.split(' ', limit = 3)} ${"a,b,,c".split(",")} ${"a1b22c".split(Regex("[0-9]+"))} ${"one two".split(" ").map { it.length }} ${"a-b_c".split('-', '_')} ${"".split(",")} ${"k=v=w".split("=", limit = 2)}")
	println("${vTrimmed.replace("the", "a")} ${vTrimmed.replace('o', '0')} ${vTrimmed.replace("THE", "a", ignoreCase = true)} ${vTrimmed.replaceFirst("o", "0")} ${vTrimmed.replace(Regex("[aeiou]"), "")} ${vTrimmed.replace(Regex("(\\w+) (\\w+)")) { "${it.groupValues[2]} ${it.groupValues[1]}" }} ${vTrimmed.filter { it.isLetter() }.length} ${vTrimmed.filterNot { it.isWhitespace() }.length} ${"a b".replace(" ", "")} ${vTrimmed.replaceRange(0, 3, "A")} ${vTrimmed.removeRange(0, 4)}")
	println("${"apple".compareTo("banana")} ${"b".compareTo("a")} ${"a".compareTo("a")} ${"Apple".compareTo("apple", ignoreCase = true)} ${"a".equals("A", ignoreCase = true)} ${"a" == "a"} ${"abc" < "abd"} ${"abc".hashCode()} ${"".hashCode()} ${listOf("b", "A", "c").sorted()} ${listOf("b", "A", "c").sortedBy { it.lowercase() }} ${"x".equals(null)} ${maxOf("a", "b")} ${"é" < "z"}")
	println("${"42".toInt()} ${"-7".toLong()} ${"3.5".toDouble()} ${"1e3".toDouble()} ${"x".toIntOrNull()} ${"".toIntOrNull()} ${" 4".toIntOrNull()} ${"2.5".toFloat()} ${"true".toBoolean()} ${"TRUE".toBoolean()} ${"yes".toBoolean()} ${"ff".toInt(16)} ${"-101".toInt(2)} ${"z".toInt(36)} ${"99999999999".toIntOrNull()} ${"99999999999".toLong()} ${"0x1f".removePrefix("0x").toInt(16)} ${"+5".toInt()} ${"1_000".toIntOrNull()} ${"NaN".toDouble().isNaN()} ${"12".toByte()} ${"70000".toShortOrNull()} ${"4000000000".toUInt()} ${"1.0".toBigDecimalOrNullSafe()}")
	println("${255.toString(16)} ${(-255).toString(2)} ${35.toString(36)} ${255.toString(8)} ${1234567890123L.toString(36)} ${5.toString()} ${1.5.toString()} ${'c'.toString()} ${true.toString()} ${null.toString()} ${3.toChar().code} ${'a'.code} ${97.toChar()} ${'a' + 1} ${'z' - 'a'} ${'5'.digitToInt()} ${'f'.digitToInt(16)} ${5.digitToChar()} ${'x'.digitToIntOrNull()}")
	println("${'a'.isLetter()} ${'5'.isDigit()} ${' '.isWhitespace()} ${'A'.isUpperCase()} ${'a'.isLowerCase()} ${'_'.isLetterOrDigit()} ${'a'.uppercaseChar()} ${'A'.lowercaseChar()} ${'ß'.uppercase()} ${'é'.isLetter()} ${'é'.uppercaseChar()} ${'1'.isLetterOrDigit()} ${'\n'.isWhitespace()} ${'a'.uppercase()} ${'ǆ'.titlecaseChar()} ${'a' in 'a'..'f'} ${'a' < 'b'} ${'a'.compareTo('b')} ${"abc".map { it.code }.sum()}")

	val vBuilder = StringBuilder("hello")
	vBuilder.append(' ').append("world").insert(0, ">> ").append(1).append(2.5).append('!').append(true).appendLine().append("next")
	vBuilder[3] = 'H'
	vBuilder.deleteAt(vBuilder.length - 1)
	vBuilder.setRange(0, 2, "<<")
	println("$vBuilder ${vBuilder.length} ${vBuilder.indexOf("world")} ${vBuilder[3]} ${vBuilder.lines().size} ${vBuilder.substring(3, 8)} ${vBuilder.reversed().take(3)} ${vBuilder.isEmpty()} ${vBuilder.toString().trim().length} ${vBuilder.deleteRange(0, 3).take(5)} ${vBuilder.lastIndexOf("l")} ${StringBuilder().append("a", "b", "c")} ${vBuilder.clear().length}")
	println(buildString { for (vIndex in 1..3) { append(vIndex); if (vIndex < 3) append(", ") }; appendLine(); append("end") })
	println(listOf("a", "b").joinToString(separator = " | ", prefix = "{", postfix = "}") { it.uppercase() })

	val vLines = """
		first line
		  second, indented
		third
	""".trimIndent()
	println("${vLines.lines()} ${vLines.lines().map { it.trim() }} ${vLines.lineSequence().count()} ${vLines.length}")
	println("""
		|margin
		|  kept
	""".trimMargin())
	println("a\tb\\n\"q\" \$x é ${'$'}{y} ${"nested ${"inner ${1 + 1}"}"}")
	println("${"hello world".split(" ").joinToString(" ") { vWord -> vWord.replaceFirstChar { it.uppercase() } }} ${"camelCaseText".map { if (it.isUpperCase()) "_${it.lowercase()}" else it.toString() }.joinToString("")} ${"a1b2".filter { it.isDigit() }.toInt() * 2} ${"hello".toList()} ${"hello".toSet().size} ${"hello".toCharArray().sorted().joinToString("")} ${"hello".zip("world").count { it.first == it.second }} ${"hello".chunked(2)} ${"hello".windowed(2).size} ${"hello".withIndex().filter { it.index % 2 == 0 }.map { it.value }} ${"hello".any { it == 'l' }} ${"hello".all { it.isLetter() }} ${"hello".none { it == 'z' }} ${"hello".firstOrNull { it > 'k' }} ${"hello".lastOrNull { it < 'f' }} ${"hello".maxOrNull()} ${"hello".indices} ${"hello".lastIndex} ${"hello".getOrNull(9)} ${"hello".getOrElse(9) { '?' }} ${"hello".single { it == 'h' }} ${"hello".partition { it in "aeiou" }} ${"hello".associateWith { it.code }.size} ${"hello".groupBy { it }.mapValues { it.value.size }} ${"hello".fold(0) { vSum, vChar -> vSum + vChar.code }} ${"hello".sumOf { it.code }} ${"hello".toList().distinct()} ${"a,b".splitToSequence(",").toList()} ${"hello".commonPrefixWith("help")} ${"hello".endsWith('o')} ${"Hello".replaceFirstChar { it.lowercaseChar() }} ${"hello".toCharArray().concatToString(1, 3)} ${"h".single()} ${"hello".subSequence(1, 3)} ${"hello".asIterable().count()} ${"hello".reversed().asSequence().take(2).toList()}")
	println("${"%d items".replace("%d", 3.toString())} ${"abc".encodeToByteArray().toList()} ${byteArrayOf(104, 105).decodeToString()} ${"é😀".encodeToByteArray().size} ${"é😀".length} ${"é😀".codePointCount()} ${"tab\there".contains('\t')} ${"line1\nline2".lines()} ${"a\r\nb".lines()} ${"x".compareTo("y") < 0} ${"abc".regionMatches(1, "xbc", 1, 2)} ${"Hello".equals("hello")} ${"a".plus("b").plus(1)} ${"" + 1 + 2} ${1.toString() + 2} ${"null" == null.toString()} ${"a" + null} ${"x".let { it + it }} ${"abc".ifEmpty { "none" }} ${"".ifEmpty { "none" }} ${" ".ifBlank { "blank" }} ${"abc".takeIf { it.length > 5 } ?: "short"} ${"text".orEmpty()} ${(null as String?).orEmpty().length} ${(null as String?).isNullOrEmpty()} ${"".isNullOrBlank()} ${(null as String?)?.length ?: -1}")
	try { "abc".toInt() } catch (inError: NumberFormatException) { println(inError.message) }
	try { "abc"[5] } catch (inError: IndexOutOfBoundsException) { println("index") }
	try { "abc".substring(2, 1) } catch (inError: IndexOutOfBoundsException) { println("range") }
	try { "".first() } catch (inError: NoSuchElementException) { println(inError.message) }
	try { "abc".toDouble() } catch (inError: NumberFormatException) { println("not a double") }
	try { "ab".single() } catch (inError: IllegalArgumentException) { println(inError.message) }
}

fun String.toBigDecimalOrNullSafe(): String = this.toDoubleOrNull()?.toString() ?: "none"
fun String.codePointCount(): Int { var vCount = 0; var vIndex = 0; while (vIndex < length) { if (this[vIndex].isHighSurrogate() && vIndex + 1 < length) vIndex++; vIndex++; vCount++ }; return vCount }
