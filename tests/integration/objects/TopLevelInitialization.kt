// Top-level properties get their values when the file is first used, in the order they are
// written; objects they refer to are created on the way, whatever order they are declared in.

object Late {
	val size = Settings.base * 2
	val label = "late " + size
}

object Settings {
	val base = 21
	val names = listOf("a", "b", "c")
}

enum class Shade(val weight: Int) {
	LIGHT(Settings.base), DARK(Settings.base + Late.size);

	companion object {
		val heaviest = entries.maxOf { it.weight }
	}
}

fun announce(inWhat: String, inValue: Int): Int {
	println("computing $inWhat")
	return inValue
}

val gFirst = announce("gFirst", 1)
val gFromObject = Late.size + Settings.names.size
val gFromEnum = Shade.heaviest
val gSecond = announce("gSecond", gFirst + 1)
var gCounter = gSecond * 10
val gTable = HashMap<String, Int>().also { it["one"] = gFirst }
val gNames = ArrayList<String>()
val gPlain = 7
val gDerived = gPlain * 6

class Reader {
	fun read(): Int = gCounter + gDerived
}

fun bump(): Int {
	gCounter++
	return gCounter
}

fun main() {
	println("main starts")
	println(gFirst + gSecond)
	println(gFromObject)
	println(gFromEnum)
	println(bump())
	println(Reader().read())
	gNames.add("x")
	gNames.add(Late.label)
	println(gNames)
	println(gTable["one"])
	println(Shade.DARK.weight)
}
