package StringOpsTest

/*
 * Exercise every public String operation declared in ktc/Strings.kt -
 * both the codegen intrinsics (length, substring, contains, lowercase, …)
 * and the pure-Kotlin extensions (first, take, trim, isBlank, …).
 *
 * Each test runs as a void function that fatalError()s on the first mismatch.
 * main() runs them in sequence and prints "done" on success.
 */

// ════════════════════════════════════════════════════════════════════
// MARK: Properties
// ════════════════════════════════════════════════════════════════════

fun testLength() {
    if ("hello".length != 5) fatalError("FAIL length")
    if ("".length     != 0) fatalError("FAIL length empty")
    if ("a".length    != 1) fatalError("FAIL length 1")
    println("length ok")
}

fun testLastIndex() {
    val li1 = "hello".lastIndex
    val li2 = "a".lastIndex
    val li3 = "".lastIndex
    if (li1 !=  4) fatalError("FAIL lastIndex non-empty: $li1")
    if (li2 !=  0) fatalError("FAIL lastIndex single: $li2")
    if (li3 != -1) fatalError("FAIL lastIndex empty: $li3")
    println("lastIndex ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Indexing & access
// ════════════════════════════════════════════════════════════════════

fun testIndexing() {
    val s = "abc"
    if (s[0] != 'a') fatalError("FAIL [0]")
    if (s[1] != 'b') fatalError("FAIL [1]")
    if (s[2] != 'c') fatalError("FAIL [2]")
    println("indexing ok")
}

fun testFirstLast() {
    if ("hello".first() != 'h') fatalError("FAIL first")
    if ("hello".last()  != 'o') fatalError("FAIL last")
    if ("x".first() != 'x') fatalError("FAIL first 1-char")
    if ("x".last()  != 'x') fatalError("FAIL last 1-char")
    println("first/last ok")
}

fun testFirstLastOrNull() {
    val a: Char? = "abc".firstOrNull()
    if (a == null || a != 'a') fatalError("FAIL firstOrNull non-empty")
    val b: Char? = "".firstOrNull()
    if (b != null) fatalError("FAIL firstOrNull empty")
    val c: Char? = "xyz".lastOrNull()
    if (c == null || c != 'z') fatalError("FAIL lastOrNull non-empty")
    val d: Char? = "".lastOrNull()
    if (d != null) fatalError("FAIL lastOrNull empty")
    println("firstOrNull/lastOrNull ok")
}

fun testGetOrNull() {
    val a: Char? = "abc".getOrNull(0)
    if (a == null || a != 'a') fatalError("FAIL getOrNull 0")
    val b: Char? = "abc".getOrNull(2)
    if (b == null || b != 'c') fatalError("FAIL getOrNull last")
    val c: Char? = "abc".getOrNull(3)
    if (c != null) fatalError("FAIL getOrNull out-of-bounds")
    val d: Char? = "abc".getOrNull(-1)
    if (d != null) fatalError("FAIL getOrNull negative")
    println("getOrNull ok")
}

fun testGetOrElse() {
    val v1 = "abc".getOrElse(1) { '?' }
    if (v1 != 'b') fatalError("FAIL getOrElse in-range: $v1")
    val v2 = "abc".getOrElse(10) { '?' }
    if (v2 != '?') fatalError("FAIL getOrElse out-of-range: $v2")
    val v3 = "abc".getOrElse(-1) { '!' }
    if (v3 != '!') fatalError("FAIL getOrElse negative: $v3")
    println("getOrElse ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Equality / comparison / hashing
// ════════════════════════════════════════════════════════════════════

fun testEquality() {
    if ("foo" != "foo")  fatalError("FAIL eq same")
    if ("foo" == "bar")  fatalError("FAIL eq diff")
    if ("foo" == "fo")   fatalError("FAIL eq diff-len")
    println("equality ok")
}

fun testCompareTo() {
    if ("abc".compareTo("abc") != 0) fatalError("FAIL compareTo equal")
    if ("abc".compareTo("abd") >= 0) fatalError("FAIL compareTo less")
    if ("abd".compareTo("abc") <= 0) fatalError("FAIL compareTo greater")
    println("compareTo ok")
}

fun testHashCode() {
    val h1 = "foo".hashCode()
    val h2 = "foo".hashCode()
    if (h1 != h2) fatalError("FAIL hashCode determinism")
    println("hashCode ok: $h1")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Concatenation & substring
// ════════════════════════════════════════════════════════════════════

inline fun cat(a: String, b: String): String = a + b

fun testPlus() {
    // `+` allocates via alloca, so we run inside an inline helper.
    val r = cat("foo", "bar")
    if (r != "foobar") fatalError("FAIL plus: $r")
    println("plus ok")
}

fun testSubstring() {
    val s = "hello world"
    if (s.substring(0, 5)  != "hello") fatalError("FAIL substring(0,5)")
    if (s.substring(6, 11) != "world") fatalError("FAIL substring(6,11)")
    if (s.substring(6)     != "world") fatalError("FAIL substring(6) [default end]")
    if (s.substring(0, 0)  != "")      fatalError("FAIL substring(0,0)")
    println("substring ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Search - startsWith / endsWith
// ════════════════════════════════════════════════════════════════════

fun testStartsEndsWith() {
    val s = "hello world"
    if (!s.startsWith("hello")) fatalError("FAIL startsWith match")
    if ( s.startsWith("world")) fatalError("FAIL startsWith mismatch")
    if (!s.startsWith(""))      fatalError("FAIL startsWith empty")
    if (!s.endsWith("world"))   fatalError("FAIL endsWith match")
    if ( s.endsWith("hello"))   fatalError("FAIL endsWith mismatch")
    if (!s.endsWith(""))        fatalError("FAIL endsWith empty")
    println("startsWith/endsWith ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Search - contains / indexOf / lastIndexOf (String + Char)
// ════════════════════════════════════════════════════════════════════

fun testContainsString() {
    val s = "hello world"
    if (!s.contains("world")) fatalError("FAIL contains-str match")
    if (!s.contains("o w"))   fatalError("FAIL contains-str middle")
    if ( s.contains("xyz"))   fatalError("FAIL contains-str mismatch")
    println("contains(String) ok")
}

fun testContainsChar() {
    val s = "hello"
    if (!s.contains('h')) fatalError("FAIL contains-char first")
    if (!s.contains('l')) fatalError("FAIL contains-char mid")
    if (!s.contains('o')) fatalError("FAIL contains-char last")
    if ( s.contains('z')) fatalError("FAIL contains-char absent")
    println("contains(Char) ok")
}

fun testIndexOfString() {
    val s = "abcabc"
    if (s.indexOf("b")   != 1)  fatalError("FAIL indexOf-str b")
    if (s.indexOf("abc") != 0)  fatalError("FAIL indexOf-str prefix")
    if (s.indexOf("z")   != -1) fatalError("FAIL indexOf-str absent")
    println("indexOf(String) ok")
}

fun testIndexOfChar() {
    val s = "hello"
    if (s.indexOf('h') != 0)  fatalError("FAIL indexOf-char first")
    if (s.indexOf('l') != 2)  fatalError("FAIL indexOf-char first-l")
    if (s.indexOf('z') != -1) fatalError("FAIL indexOf-char absent")
    println("indexOf(Char) ok")
}

fun testLastIndexOfString() {
    val s = "abcabc"
    if (s.lastIndexOf("b")   != 4)  fatalError("FAIL lastIndexOf-str b")
    if (s.lastIndexOf("abc") != 3)  fatalError("FAIL lastIndexOf-str abc")
    if (s.lastIndexOf("z")   != -1) fatalError("FAIL lastIndexOf-str absent")
    println("lastIndexOf(String) ok")
}

fun testLastIndexOfChar() {
    val s = "hello"
    if (s.lastIndexOf('l') != 3)  fatalError("FAIL lastIndexOf-char last-l")
    if (s.lastIndexOf('h') != 0)  fatalError("FAIL lastIndexOf-char first")
    if (s.lastIndexOf('z') != -1) fatalError("FAIL lastIndexOf-char absent")
    println("lastIndexOf(Char) ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Slicing extensions (substring views)
// ════════════════════════════════════════════════════════════════════

fun testTake() {
    if ("hello".take(0) != "")      fatalError("FAIL take(0)")
    if ("hello".take(3) != "hel")   fatalError("FAIL take(3)")
    if ("hello".take(5) != "hello") fatalError("FAIL take(len)")
    if ("hello".take(99)!= "hello") fatalError("FAIL take(over)")
    println("take ok")
}

fun testTakeLast() {
    if ("hello".takeLast(0) != "")      fatalError("FAIL takeLast(0)")
    if ("hello".takeLast(3) != "llo")   fatalError("FAIL takeLast(3)")
    if ("hello".takeLast(5) != "hello") fatalError("FAIL takeLast(len)")
    if ("hello".takeLast(99)!= "hello") fatalError("FAIL takeLast(over)")
    println("takeLast ok")
}

fun testDrop() {
    if ("hello".drop(0) != "hello") fatalError("FAIL drop(0)")
    if ("hello".drop(2) != "llo")   fatalError("FAIL drop(2)")
    if ("hello".drop(5) != "")      fatalError("FAIL drop(len)")
    if ("hello".drop(99)!= "")      fatalError("FAIL drop(over)")
    println("drop ok")
}

fun testDropLast() {
    if ("hello".dropLast(0) != "hello") fatalError("FAIL dropLast(0)")
    if ("hello".dropLast(2) != "hel")   fatalError("FAIL dropLast(2)")
    if ("hello".dropLast(5) != "")      fatalError("FAIL dropLast(len)")
    if ("hello".dropLast(99)!= "")      fatalError("FAIL dropLast(over)")
    println("dropLast ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Trim & blank
// ════════════════════════════════════════════════════════════════════

fun testTrim() {
    if ("  hello  ".trim() != "hello")   fatalError("FAIL trim spaces")
    if ("\thello\n".trim() != "hello")   fatalError("FAIL trim tab+nl")
    if ("hello".trim()     != "hello")   fatalError("FAIL trim none")
    if ("   ".trim()       != "")        fatalError("FAIL trim all-blank")
    if ("".trim()          != "")        fatalError("FAIL trim empty")
    println("trim ok")
}

fun testTrimStart() {
    if ("  abc".trimStart()    != "abc")    fatalError("FAIL trimStart")
    if ("abc  ".trimStart()    != "abc  ")  fatalError("FAIL trimStart right untouched")
    if ("\t\nabc".trimStart()  != "abc")    fatalError("FAIL trimStart whitespace")
    println("trimStart ok")
}

fun testTrimEnd() {
    if ("abc  ".trimEnd()   != "abc")   fatalError("FAIL trimEnd")
    if ("  abc".trimEnd()   != "  abc") fatalError("FAIL trimEnd left untouched")
    if ("abc\n\t".trimEnd() != "abc")   fatalError("FAIL trimEnd whitespace")
    println("trimEnd ok")
}

fun testIsBlank() {
    if (!"".isBlank())       fatalError("FAIL isBlank empty")
    if (!"  ".isBlank())     fatalError("FAIL isBlank spaces")
    if (!"\t\n\r ".isBlank()) fatalError("FAIL isBlank all-ws")
    if ("a".isBlank())       fatalError("FAIL isBlank with char")
    if (" a ".isBlank())     fatalError("FAIL isBlank middle char")
    println("isBlank ok")
}

fun testIsNotBlank() {
    if ("".isNotBlank())       fatalError("FAIL isNotBlank empty")
    if ("  ".isNotBlank())     fatalError("FAIL isNotBlank spaces")
    if (!"hello".isNotBlank()) fatalError("FAIL isNotBlank text")
    if (!" x ".isNotBlank())   fatalError("FAIL isNotBlank padded")
    println("isNotBlank ok")
}

fun testIsEmpty() {
    if (!"".isEmpty())     fatalError("FAIL isEmpty empty")
    if ("x".isEmpty())     fatalError("FAIL isEmpty non-empty")
    if ("".isNotEmpty())   fatalError("FAIL isNotEmpty empty")
    if (!"x".isNotEmpty()) fatalError("FAIL isNotEmpty non-empty")
    if (!" ".isNotEmpty()) fatalError("FAIL isNotEmpty space-only")  // contains a space, not empty
    println("isEmpty/isNotEmpty ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Buffer-producing operations (alloca-based)
// ════════════════════════════════════════════════════════════════════

fun testReversed() {
    if ("hello".reversed() != "olleh") fatalError("FAIL reversed")
    if ("a".reversed()     != "a")     fatalError("FAIL reversed single")
    if ("".reversed()      != "")      fatalError("FAIL reversed empty")
    if ("12321".reversed() != "12321") fatalError("FAIL reversed palindrome")
    println("reversed ok")
}

fun testLowercase() {
    if ("HELLO".lowercase()    != "hello")        fatalError("FAIL lowercase all-upper")
    if ("hello".lowercase()    != "hello")        fatalError("FAIL lowercase all-lower")
    if ("HeLLo".lowercase()    != "hello")        fatalError("FAIL lowercase mixed")
    if ("Hello, World!".lowercase() != "hello, world!") fatalError("FAIL lowercase keep-punct")
    if ("".lowercase()         != "")             fatalError("FAIL lowercase empty")
    println("lowercase ok")
}

fun testUppercase() {
    if ("hello".uppercase()    != "HELLO")        fatalError("FAIL uppercase all-lower")
    if ("HELLO".uppercase()    != "HELLO")        fatalError("FAIL uppercase all-upper")
    if ("HeLLo".uppercase()    != "HELLO")        fatalError("FAIL uppercase mixed")
    if ("Hello, World!".uppercase() != "HELLO, WORLD!") fatalError("FAIL uppercase keep-punct")
    if ("".uppercase()         != "")             fatalError("FAIL uppercase empty")
    println("uppercase ok")
}

fun testRepeat() {
    if ("ab".repeat(3) != "ababab") fatalError("FAIL repeat(3)")
    if ("x".repeat(5)  != "xxxxx")  fatalError("FAIL repeat(5)")
    if ("hi".repeat(1) != "hi")     fatalError("FAIL repeat(1)")
    if ("hi".repeat(0) != "")       fatalError("FAIL repeat(0)")
    if ("".repeat(99)  != "")       fatalError("FAIL repeat empty")
    println("repeat ok")
}

fun testReplaceChar() {
    if ("hello".replace('l', 'L') != "heLLo") fatalError("FAIL replace l→L")
    if ("aaaa".replace('a', 'b')  != "bbbb")  fatalError("FAIL replace all")
    if ("xyz".replace('a', 'b')   != "xyz")   fatalError("FAIL replace none-present")
    if ("".replace('a', 'b')      != "")      fatalError("FAIL replace empty")
    println("replace(Char,Char) ok")
}

fun testPadStart() {
    if ("42".padStart(5, '0')     != "00042") fatalError("FAIL padStart zeros")
    if ("hello".padStart(3, '.')  != "hello") fatalError("FAIL padStart no-op (already long)")
    if ("x".padStart(4)           != "   x")  fatalError("FAIL padStart default space")
    if ("".padStart(3, '#')       != "###")   fatalError("FAIL padStart empty")
    println("padStart ok")
}

fun testPadEnd() {
    if ("42".padEnd(5, '_')       != "42___") fatalError("FAIL padEnd underscore")
    if ("hello".padEnd(3, '.')    != "hello") fatalError("FAIL padEnd no-op")
    if ("x".padEnd(4)             != "x   ")  fatalError("FAIL padEnd default space")
    if ("".padEnd(3, '#')         != "###")   fatalError("FAIL padEnd empty")
    println("padEnd ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Numeric parsing
// ════════════════════════════════════════════════════════════════════

fun testToInt() {
    if ("42".toInt()     != 42)      fatalError("FAIL toInt 42")
    if ("-17".toInt()    != -17)     fatalError("FAIL toInt -17")
    if ("0".toInt()      != 0)       fatalError("FAIL toInt 0")
    println("toInt ok")
}

fun testToIntOrNull() {
    val a: Int? = "42".toIntOrNull()
    if (a == null || a != 42) fatalError("FAIL toIntOrNull valid")
    val b: Int? = "abc".toIntOrNull()
    if (b != null) fatalError("FAIL toIntOrNull invalid")
    val c: Int? = "".toIntOrNull()
    if (c != null) fatalError("FAIL toIntOrNull empty")
    println("toIntOrNull ok")
}

fun testToLong() {
    if ("9999999999".toLong() != 9999999999L) fatalError("FAIL toLong big")
    if ("-1".toLong() != -1L)                  fatalError("FAIL toLong -1")
    println("toLong ok")
}

fun testToBoolean() {
    if (!"true".toBooleanStrict())  fatalError("FAIL toBooleanStrict true")
    if ("false".toBooleanStrict())  fatalError("FAIL toBooleanStrict false")

    val a: Boolean? = "true".toBooleanStrictOrNull()
    if (a == null || a != true)  fatalError("FAIL toBooleanStrictOrNull true")
    val b: Boolean? = "false".toBooleanStrictOrNull()
    if (b == null || b != false) fatalError("FAIL toBooleanStrictOrNull false")
    val c: Boolean? = "maybe".toBooleanStrictOrNull()
    if (c != null) fatalError("FAIL toBooleanStrictOrNull invalid")
    val d: Boolean? = "".toBooleanStrictOrNull()
    if (d != null) fatalError("FAIL toBooleanStrictOrNull empty")
    println("toBoolean ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Entry
// ════════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════════
// MARK: Raw triple-quoted strings
// ════════════════════════════════════════════════════════════════════

fun testRawStringNoEscape() {
    // Backslashes inside """...""" are literal, not escape introducers.
    val s = """a\nb"""
    if (s.length != 4) fatalError("FAIL raw length")
    if (s[0] != 'a')   fatalError("FAIL raw [0]")
    if (s[1] != '\\')  fatalError("FAIL raw [1] not backslash")
    if (s[2] != 'n')   fatalError("FAIL raw [2]")
    if (s[3] != 'b')   fatalError("FAIL raw [3]")
    println("raw no-escape ok")
}

fun testRawStringMultiline() {
    // A literal newline between the two lines is preserved verbatim.
    val s = """line1
line2"""
    if (s.length != 11)   fatalError("FAIL multiline length")
    if (s[5]   != '\n')   fatalError("FAIL multiline newline at idx 5")
    if (!s.startsWith("line1")) fatalError("FAIL multiline starts")
    if (!s.endsWith("line2"))   fatalError("FAIL multiline ends")
    println("raw multiline ok")
}

fun testRawStringEmbeddedQuote() {
    // A single embedded `"` inside the raw block is fine; only `"""` closes.
    val s = """a "b" c"""
    if (s.length != 7) fatalError("FAIL embedded quote length")
    if (s[2] != '"')   fatalError("FAIL embedded quote at idx 2")
    if (s[4] != '"')   fatalError("FAIL embedded quote at idx 4")
    println("raw embedded quote ok")
}

fun main() {
    testLength()
    testLastIndex()
    testIndexing()
    testFirstLast()
    testFirstLastOrNull()
    testGetOrNull()
    testGetOrElse()
    testEquality()
    testCompareTo()
    testHashCode()
    testPlus()
    testSubstring()
    testStartsEndsWith()
    testContainsString()
    testContainsChar()
    testIndexOfString()
    testIndexOfChar()
    testLastIndexOfString()
    testLastIndexOfChar()
    testTake()
    testTakeLast()
    testDrop()
    testDropLast()
    testTrim()
    testTrimStart()
    testTrimEnd()
    testIsBlank()
    testIsNotBlank()
    testIsEmpty()
    testReversed()
    testLowercase()
    testUppercase()
    testRepeat()
    testReplaceChar()
    testPadStart()
    testPadEnd()
    testToInt()
    testToIntOrNull()
    testToLong()
    testToBoolean()
    testRawStringNoEscape()
    testRawStringMultiline()
    testRawStringEmbeddedQuote()
    println("done")
}
