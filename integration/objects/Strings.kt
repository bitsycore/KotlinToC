package objects

class Tag(val name: String) {
	override fun toString(): String = "#$name"
}

val greeting = "hello"
var journal = ""

fun shout(s: String): String = s + "!"

fun repeatText(s: String, times: Int): String {
	var vOut = ""
	for (vI in 0 until times) vOut += s
	return vOut
}

fun describe(s: String?): String = if (s == null) "nothing" else "'$s' (${s.length})"

fun pick(flag: Boolean): String? = if (flag) "yes" else null

fun log(line: String) {
	journal = journal + line + ";"
}

fun countChar(s: String, c: Char): Int {
	var vCount = 0
	for (vI in 0 until s.length) {
		if (s[vI] == c) vCount++
	}
	return vCount
}

fun main() {
	println(greeting)
	println(shout(greeting))
	println(shout(shout("a")))
	println(repeatText("ab", 4))
	println(repeatText("x", 0).length)

	// Templates with every kind of value
	val vTag = Tag("kotlin")
	val vMaybe: Tag? = null
	val vNumber: Int? = pick(true)?.length
	val vMissing: Int? = pick(false)?.length
	println("i=${42} l=${7L} d=${1.5} f=${2.5f} b=${true} c=${'z'} u=${3u}")
	println("tag=$vTag none=$vMaybe n=$vNumber m=$vMissing s=${pick(false)}")
	println(describe("abc") + " " + describe(null))
	println("" + 1 + 2 + "3" + 4.0 + vTag)
	println(1.toString() + 2.5.toString() + true.toString() + 'c'.toString())
	println(vTag.toString().length)

	// Equality, comparison, hash
	val vBuilt = "hel" + "lo"
	println(vBuilt == greeting)
	println(vBuilt === greeting)
	println(vBuilt != "help")
	println("apple".compareTo("banana") < 0)
	println("b".compareTo("a"))
	println("abc".compareTo("abcd"))
	println("hello".hashCode())
	println("".hashCode())
	println(pick(false) == null)
	println(pick(true) == "yes")
	val vAny: Any = vBuilt
	println(vAny == greeting)
	println(vAny.toString())

	// Unicode: length and indexing count UTF-16 units, as in Kotlin.
	val vAccent = "héllo wörld"
	println(vAccent.length)
	println(vAccent[1])
	println(vAccent[7])
	println(countChar(vAccent, 'l'))
	val vEmoji = "a😀b"
	println(vEmoji.length)
	println(vEmoji[3])
	println(vEmoji[1].code)
	println("日本語".length)
	println("日本語"[2])
	println("héllo".hashCode())
	println("é".compareTo("z"))
	println(vAccent + vEmoji)

	// A long string outgrows the builder's stack buffer.
	val vLong = repeatText("0123456789", 30)
	println(vLong.length)
	println(vLong[299])

	log("start")
	log(shout("mid"))
	log("end")
	println(journal)
	val vNull: String? = null
	println(vNull)
	println("value: $vNull")
	println(vNull + "x")
}
