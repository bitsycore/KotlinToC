package stdlib

class Item(val name: String, var qty: Int) {
	override fun toString(): String = "$name x$qty"
}

class Config {
	var host = ""
	var port = 0
	var tags = arrayOf<String>()
}

fun find(items: Array<Item>, name: String): Item? {
	for (vItem in items) {
		if (vItem.name == name) return vItem
	}
	return null
}

fun describe(value: Int?): String = value?.let { "value $it" } ?: "nothing"

fun main() {
	// apply / also: configure and return the receiver
	val vConfig = Config().apply {
		host = "localhost"
		port = 8080
		tags = arrayOf("a", "b")
	}
	println("${vConfig.host}:${vConfig.port} ${vConfig.tags.size}")
	val vItem = Item("bolt", 1).apply { qty += 2 }.also { println("made $it") }

	// let / run / with: compute a value
	val vDoubled = vItem.let { it.qty * 2 }
	println(vDoubled)
	println(with(vItem) { name + qty })
	println(vItem.run { "$name!" })
	println(run { 40 + 2 })
	println(describe(5) + " / " + describe(null))

	// takeIf / takeUnless with safe calls
	val vItems = arrayOf(vItem, Item("nut", 9))
	println(find(vItems, "nut")?.takeIf { it.qty > 5 }?.name ?: "none")
	println(find(vItems, "nut")?.takeUnless { it.qty > 5 }?.name ?: "none")
	println(find(vItems, "gear")?.let { it.qty } ?: -1)
	find(vItems, "bolt")?.let {
		it.qty = 0
		println("emptied ${it.name}")
	}
	println(vItem)

	// repeat, with non-local control flow
	var vSum = 0
	repeat(5) { vSum += it }
	println(vSum)
	repeat(3) { vOuter ->
		repeat(2) { vInner -> print("$vOuter$vInner ") }
	}
	println()

	// Chains and nesting
	val vText = "kotlin".let { it + "2" }.also { print("[$it] ") }.run { length }
	println(vText)
	val vNested = vItem.let { vOuter -> vItems[1].let { vInner -> vOuter.qty + vInner.qty } }
	println(vNested)
	val vCount = vItems.let { it.size }.takeIf { it > 1 } ?: 0
	println(vCount)
}
