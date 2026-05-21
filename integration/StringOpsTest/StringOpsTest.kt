package StringOpsTest

fun testLength() {
    val s = "hello"
    if (s.length != 5) error("FAIL length: ${s.length}")
    val empty = ""
    if (empty.length != 0) error("FAIL length empty: ${empty.length}")
    println("length ok")
}

fun testIsEmpty() {
    if (!"".isEmpty())  error("FAIL isEmpty 1")
    if ("x".isEmpty())  error("FAIL isEmpty 2")
    if ("".isNotEmpty()) error("FAIL isNotEmpty 1")
    if (!"x".isNotEmpty()) error("FAIL isNotEmpty 2")
    println("isEmpty ok")
}

fun testStartsEndsWith() {
    val s = "hello world"
    if (!s.startsWith("hello")) error("FAIL startsWith 1")
    if ( s.startsWith("world")) error("FAIL startsWith 2")
    if (!s.endsWith("world"))   error("FAIL endsWith 1")
    if ( s.endsWith("hello"))   error("FAIL endsWith 2")
    if (!s.startsWith(""))      error("FAIL startsWith empty")
    println("startsWith/endsWith ok")
}

fun testContains() {
    val s = "hello world"
    if (!s.contains("world"))  error("FAIL contains 1")
    if (!s.contains("hello"))  error("FAIL contains 2")
    if (!s.contains("o w"))    error("FAIL contains 3")
    if ( s.contains("xyz"))    error("FAIL contains 4")
    println("contains ok")
}

fun testIndexOf() {
    val s = "abcabc"
    val i1 = s.indexOf("b")
    if (i1 != 1) error("FAIL indexOf b: $i1")
    val i2 = s.indexOf("z")
    if (i2 != -1) error("FAIL indexOf z: $i2")
    val i3 = s.indexOf("abc")
    if (i3 != 0) error("FAIL indexOf abc: $i3")
    println("indexOf ok: $i1, $i2, $i3")
}

fun testSubstring() {
    val s = "hello world"
    val sub1 = s.substring(0, 5)
    if (sub1 != "hello") error("FAIL substring1: $sub1")
    val sub2 = s.substring(6, 11)
    if (sub2 != "world") error("FAIL substring2: $sub2")
    val sub3 = s.substring(6)
    if (sub3 != "world") error("FAIL substring3: $sub3")
    println("substring ok")
}

fun testEquality() {
    val a = "foo"
    val b = "foo"
    val c = "bar"
    if (a != b) error("FAIL eq 1")
    if (a == c) error("FAIL eq 2")
    println("equality ok")
}

fun main() {
    testLength()
    testIsEmpty()
    testStartsEndsWith()
    testContains()
    testIndexOf()
    testSubstring()
    testEquality()
    println("done")
}
