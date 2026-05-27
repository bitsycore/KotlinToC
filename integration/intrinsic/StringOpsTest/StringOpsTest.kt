package StringOpsTest

/*
 * Exercise every public String operation declared in ktc/Strings.kt —
 * both the codegen intrinsics (length, substring, contains, lowercase, …)
 * and the pure-Kotlin extensions (first, take, trim, isBlank, …).
 *
 * Each test runs as a void function that error()s on the first mismatch.
 * main() runs them in sequence and prints "done" on success.
 */

// ════════════════════════════════════════════════════════════════════
// MARK: Properties
// ════════════════════════════════════════════════════════════════════

fun testLength() {
    if ("hello".length != 5) error("FAIL length")
    if ("".length     != 0) error("FAIL length empty")
    if ("a".length    != 1) error("FAIL length 1")
    println("length ok")
}

fun testLastIndex() {
    val li1 = "hello".lastIndex
    val li2 = "a".lastIndex
    val li3 = "".lastIndex
    if (li1 !=  4) error("FAIL lastIndex non-empty: $li1")
    if (li2 !=  0) error("FAIL lastIndex single: $li2")
    if (li3 != -1) error("FAIL lastIndex empty: $li3")
    println("lastIndex ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Indexing & access
// ════════════════════════════════════════════════════════════════════

fun testIndexing() {
    val s = "abc"
    if (s[0] != 'a') error("FAIL [0]")
    if (s[1] != 'b') error("FAIL [1]")
    if (s[2] != 'c') error("FAIL [2]")
    println("indexing ok")
}

fun testFirstLast() {
    if ("hello".first() != 'h') error("FAIL first")
    if ("hello".last()  != 'o') error("FAIL last")
    if ("x".first() != 'x') error("FAIL first 1-char")
    if ("x".last()  != 'x') error("FAIL last 1-char")
    println("first/last ok")
}

fun testFirstLastOrNull() {
    val a: Char? = "abc".firstOrNull()
    if (a == null || a != 'a') error("FAIL firstOrNull non-empty")
    val b: Char? = "".firstOrNull()
    if (b != null) error("FAIL firstOrNull empty")
    val c: Char? = "xyz".lastOrNull()
    if (c == null || c != 'z') error("FAIL lastOrNull non-empty")
    val d: Char? = "".lastOrNull()
    if (d != null) error("FAIL lastOrNull empty")
    println("firstOrNull/lastOrNull ok")
}

fun testGetOrNull() {
    val a: Char? = "abc".getOrNull(0)
    if (a == null || a != 'a') error("FAIL getOrNull 0")
    val b: Char? = "abc".getOrNull(2)
    if (b == null || b != 'c') error("FAIL getOrNull last")
    val c: Char? = "abc".getOrNull(3)
    if (c != null) error("FAIL getOrNull out-of-bounds")
    val d: Char? = "abc".getOrNull(-1)
    if (d != null) error("FAIL getOrNull negative")
    println("getOrNull ok")
}

fun testGetOrElse() {
    val v1 = "abc".getOrElse(1) { '?' }
    if (v1 != 'b') error("FAIL getOrElse in-range: $v1")
    val v2 = "abc".getOrElse(10) { '?' }
    if (v2 != '?') error("FAIL getOrElse out-of-range: $v2")
    val v3 = "abc".getOrElse(-1) { '!' }
    if (v3 != '!') error("FAIL getOrElse negative: $v3")
    println("getOrElse ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Equality / comparison / hashing
// ════════════════════════════════════════════════════════════════════

fun testEquality() {
    if ("foo" != "foo")  error("FAIL eq same")
    if ("foo" == "bar")  error("FAIL eq diff")
    if ("foo" == "fo")   error("FAIL eq diff-len")
    println("equality ok")
}

fun testCompareTo() {
    if ("abc".compareTo("abc") != 0) error("FAIL compareTo equal")
    if ("abc".compareTo("abd") >= 0) error("FAIL compareTo less")
    if ("abd".compareTo("abc") <= 0) error("FAIL compareTo greater")
    println("compareTo ok")
}

fun testHashCode() {
    val h1 = "foo".hashCode()
    val h2 = "foo".hashCode()
    if (h1 != h2) error("FAIL hashCode determinism")
    println("hashCode ok: $h1")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Concatenation & substring
// ════════════════════════════════════════════════════════════════════

inline fun cat(a: String, b: String): String = a + b

fun testPlus() {
    // `+` allocates via alloca, so we run inside an inline helper.
    val r = cat("foo", "bar")
    if (r != "foobar") error("FAIL plus: $r")
    println("plus ok")
}

fun testSubstring() {
    val s = "hello world"
    if (s.substring(0, 5)  != "hello") error("FAIL substring(0,5)")
    if (s.substring(6, 11) != "world") error("FAIL substring(6,11)")
    if (s.substring(6)     != "world") error("FAIL substring(6) [default end]")
    if (s.substring(0, 0)  != "")      error("FAIL substring(0,0)")
    println("substring ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Search — startsWith / endsWith
// ════════════════════════════════════════════════════════════════════

fun testStartsEndsWith() {
    val s = "hello world"
    if (!s.startsWith("hello")) error("FAIL startsWith match")
    if ( s.startsWith("world")) error("FAIL startsWith mismatch")
    if (!s.startsWith(""))      error("FAIL startsWith empty")
    if (!s.endsWith("world"))   error("FAIL endsWith match")
    if ( s.endsWith("hello"))   error("FAIL endsWith mismatch")
    if (!s.endsWith(""))        error("FAIL endsWith empty")
    println("startsWith/endsWith ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Search — contains / indexOf / lastIndexOf (String + Char)
// ════════════════════════════════════════════════════════════════════

fun testContainsString() {
    val s = "hello world"
    if (!s.contains("world")) error("FAIL contains-str match")
    if (!s.contains("o w"))   error("FAIL contains-str middle")
    if ( s.contains("xyz"))   error("FAIL contains-str mismatch")
    println("contains(String) ok")
}

fun testContainsChar() {
    val s = "hello"
    if (!s.contains('h')) error("FAIL contains-char first")
    if (!s.contains('l')) error("FAIL contains-char mid")
    if (!s.contains('o')) error("FAIL contains-char last")
    if ( s.contains('z')) error("FAIL contains-char absent")
    println("contains(Char) ok")
}

fun testIndexOfString() {
    val s = "abcabc"
    if (s.indexOf("b")   != 1)  error("FAIL indexOf-str b")
    if (s.indexOf("abc") != 0)  error("FAIL indexOf-str prefix")
    if (s.indexOf("z")   != -1) error("FAIL indexOf-str absent")
    println("indexOf(String) ok")
}

fun testIndexOfChar() {
    val s = "hello"
    if (s.indexOf('h') != 0)  error("FAIL indexOf-char first")
    if (s.indexOf('l') != 2)  error("FAIL indexOf-char first-l")
    if (s.indexOf('z') != -1) error("FAIL indexOf-char absent")
    println("indexOf(Char) ok")
}

fun testLastIndexOfString() {
    val s = "abcabc"
    if (s.lastIndexOf("b")   != 4)  error("FAIL lastIndexOf-str b")
    if (s.lastIndexOf("abc") != 3)  error("FAIL lastIndexOf-str abc")
    if (s.lastIndexOf("z")   != -1) error("FAIL lastIndexOf-str absent")
    println("lastIndexOf(String) ok")
}

fun testLastIndexOfChar() {
    val s = "hello"
    if (s.lastIndexOf('l') != 3)  error("FAIL lastIndexOf-char last-l")
    if (s.lastIndexOf('h') != 0)  error("FAIL lastIndexOf-char first")
    if (s.lastIndexOf('z') != -1) error("FAIL lastIndexOf-char absent")
    println("lastIndexOf(Char) ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Slicing extensions (substring views)
// ════════════════════════════════════════════════════════════════════

fun testTake() {
    if ("hello".take(0) != "")      error("FAIL take(0)")
    if ("hello".take(3) != "hel")   error("FAIL take(3)")
    if ("hello".take(5) != "hello") error("FAIL take(len)")
    if ("hello".take(99)!= "hello") error("FAIL take(over)")
    println("take ok")
}

fun testTakeLast() {
    if ("hello".takeLast(0) != "")      error("FAIL takeLast(0)")
    if ("hello".takeLast(3) != "llo")   error("FAIL takeLast(3)")
    if ("hello".takeLast(5) != "hello") error("FAIL takeLast(len)")
    if ("hello".takeLast(99)!= "hello") error("FAIL takeLast(over)")
    println("takeLast ok")
}

fun testDrop() {
    if ("hello".drop(0) != "hello") error("FAIL drop(0)")
    if ("hello".drop(2) != "llo")   error("FAIL drop(2)")
    if ("hello".drop(5) != "")      error("FAIL drop(len)")
    if ("hello".drop(99)!= "")      error("FAIL drop(over)")
    println("drop ok")
}

fun testDropLast() {
    if ("hello".dropLast(0) != "hello") error("FAIL dropLast(0)")
    if ("hello".dropLast(2) != "hel")   error("FAIL dropLast(2)")
    if ("hello".dropLast(5) != "")      error("FAIL dropLast(len)")
    if ("hello".dropLast(99)!= "")      error("FAIL dropLast(over)")
    println("dropLast ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Trim & blank
// ════════════════════════════════════════════════════════════════════

fun testTrim() {
    if ("  hello  ".trim() != "hello")   error("FAIL trim spaces")
    if ("\thello\n".trim() != "hello")   error("FAIL trim tab+nl")
    if ("hello".trim()     != "hello")   error("FAIL trim none")
    if ("   ".trim()       != "")        error("FAIL trim all-blank")
    if ("".trim()          != "")        error("FAIL trim empty")
    println("trim ok")
}

fun testTrimStart() {
    if ("  abc".trimStart()    != "abc")    error("FAIL trimStart")
    if ("abc  ".trimStart()    != "abc  ")  error("FAIL trimStart right untouched")
    if ("\t\nabc".trimStart()  != "abc")    error("FAIL trimStart whitespace")
    println("trimStart ok")
}

fun testTrimEnd() {
    if ("abc  ".trimEnd()   != "abc")   error("FAIL trimEnd")
    if ("  abc".trimEnd()   != "  abc") error("FAIL trimEnd left untouched")
    if ("abc\n\t".trimEnd() != "abc")   error("FAIL trimEnd whitespace")
    println("trimEnd ok")
}

fun testIsBlank() {
    if (!"".isBlank())       error("FAIL isBlank empty")
    if (!"  ".isBlank())     error("FAIL isBlank spaces")
    if (!"\t\n\r ".isBlank()) error("FAIL isBlank all-ws")
    if ("a".isBlank())       error("FAIL isBlank with char")
    if (" a ".isBlank())     error("FAIL isBlank middle char")
    println("isBlank ok")
}

fun testIsNotBlank() {
    if ("".isNotBlank())       error("FAIL isNotBlank empty")
    if ("  ".isNotBlank())     error("FAIL isNotBlank spaces")
    if (!"hello".isNotBlank()) error("FAIL isNotBlank text")
    if (!" x ".isNotBlank())   error("FAIL isNotBlank padded")
    println("isNotBlank ok")
}

fun testIsEmpty() {
    if (!"".isEmpty())     error("FAIL isEmpty empty")
    if ("x".isEmpty())     error("FAIL isEmpty non-empty")
    if ("".isNotEmpty())   error("FAIL isNotEmpty empty")
    if (!"x".isNotEmpty()) error("FAIL isNotEmpty non-empty")
    if (!" ".isNotEmpty()) error("FAIL isNotEmpty space-only")  // contains a space, not empty
    println("isEmpty/isNotEmpty ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Buffer-producing operations (alloca-based)
// ════════════════════════════════════════════════════════════════════

fun testReversed() {
    if ("hello".reversed() != "olleh") error("FAIL reversed")
    if ("a".reversed()     != "a")     error("FAIL reversed single")
    if ("".reversed()      != "")      error("FAIL reversed empty")
    if ("12321".reversed() != "12321") error("FAIL reversed palindrome")
    println("reversed ok")
}

fun testLowercase() {
    if ("HELLO".lowercase()    != "hello")        error("FAIL lowercase all-upper")
    if ("hello".lowercase()    != "hello")        error("FAIL lowercase all-lower")
    if ("HeLLo".lowercase()    != "hello")        error("FAIL lowercase mixed")
    if ("Hello, World!".lowercase() != "hello, world!") error("FAIL lowercase keep-punct")
    if ("".lowercase()         != "")             error("FAIL lowercase empty")
    println("lowercase ok")
}

fun testUppercase() {
    if ("hello".uppercase()    != "HELLO")        error("FAIL uppercase all-lower")
    if ("HELLO".uppercase()    != "HELLO")        error("FAIL uppercase all-upper")
    if ("HeLLo".uppercase()    != "HELLO")        error("FAIL uppercase mixed")
    if ("Hello, World!".uppercase() != "HELLO, WORLD!") error("FAIL uppercase keep-punct")
    if ("".uppercase()         != "")             error("FAIL uppercase empty")
    println("uppercase ok")
}

fun testRepeat() {
    if ("ab".repeat(3) != "ababab") error("FAIL repeat(3)")
    if ("x".repeat(5)  != "xxxxx")  error("FAIL repeat(5)")
    if ("hi".repeat(1) != "hi")     error("FAIL repeat(1)")
    if ("hi".repeat(0) != "")       error("FAIL repeat(0)")
    if ("".repeat(99)  != "")       error("FAIL repeat empty")
    println("repeat ok")
}

fun testReplaceChar() {
    if ("hello".replace('l', 'L') != "heLLo") error("FAIL replace l→L")
    if ("aaaa".replace('a', 'b')  != "bbbb")  error("FAIL replace all")
    if ("xyz".replace('a', 'b')   != "xyz")   error("FAIL replace none-present")
    if ("".replace('a', 'b')      != "")      error("FAIL replace empty")
    println("replace(Char,Char) ok")
}

fun testPadStart() {
    if ("42".padStart(5, '0')     != "00042") error("FAIL padStart zeros")
    if ("hello".padStart(3, '.')  != "hello") error("FAIL padStart no-op (already long)")
    if ("x".padStart(4)           != "   x")  error("FAIL padStart default space")
    if ("".padStart(3, '#')       != "###")   error("FAIL padStart empty")
    println("padStart ok")
}

fun testPadEnd() {
    if ("42".padEnd(5, '_')       != "42___") error("FAIL padEnd underscore")
    if ("hello".padEnd(3, '.')    != "hello") error("FAIL padEnd no-op")
    if ("x".padEnd(4)             != "x   ")  error("FAIL padEnd default space")
    if ("".padEnd(3, '#')         != "###")   error("FAIL padEnd empty")
    println("padEnd ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Numeric parsing
// ════════════════════════════════════════════════════════════════════

fun testToInt() {
    if ("42".toInt()     != 42)      error("FAIL toInt 42")
    if ("-17".toInt()    != -17)     error("FAIL toInt -17")
    if ("0".toInt()      != 0)       error("FAIL toInt 0")
    println("toInt ok")
}

fun testToIntOrNull() {
    val a: Int? = "42".toIntOrNull()
    if (a == null || a != 42) error("FAIL toIntOrNull valid")
    val b: Int? = "abc".toIntOrNull()
    if (b != null) error("FAIL toIntOrNull invalid")
    val c: Int? = "".toIntOrNull()
    if (c != null) error("FAIL toIntOrNull empty")
    println("toIntOrNull ok")
}

fun testToLong() {
    if ("9999999999".toLong() != 9999999999L) error("FAIL toLong big")
    if ("-1".toLong() != -1L)                  error("FAIL toLong -1")
    println("toLong ok")
}

fun testToBoolean() {
    if (!"true".toBooleanStrict())  error("FAIL toBooleanStrict true")
    if ("false".toBooleanStrict())  error("FAIL toBooleanStrict false")

    val a: Boolean? = "true".toBooleanStrictOrNull()
    if (a == null || a != true)  error("FAIL toBooleanStrictOrNull true")
    val b: Boolean? = "false".toBooleanStrictOrNull()
    if (b == null || b != false) error("FAIL toBooleanStrictOrNull false")
    val c: Boolean? = "maybe".toBooleanStrictOrNull()
    if (c != null) error("FAIL toBooleanStrictOrNull invalid")
    val d: Boolean? = "".toBooleanStrictOrNull()
    if (d != null) error("FAIL toBooleanStrictOrNull empty")
    println("toBoolean ok")
}

// ════════════════════════════════════════════════════════════════════
// MARK: Entry
// ════════════════════════════════════════════════════════════════════

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
    println("done")
}
