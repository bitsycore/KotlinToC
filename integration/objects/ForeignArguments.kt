/** A member that takes an object of its own class, asked through a view that takes any object: as the JVM's bridge, what is not of that class is not looked at. */
enum class Color { RED, GREEN }

class Box(val name: String)

class Boxes : AbstractList<Box>() {
	private val items = listOf(Box("a"))
	override val size: Int get() = items.size
	override fun get(index: Int): Box = items[index]
	override fun contains(element: Box): Boolean = element.name == "a"
	override fun indexOf(element: Box): Int = if (element.name == "a") 0 else -1
}

fun main() {
	val entries: List<Any?> = Color.entries
	println(entries.contains(Color.GREEN))
	println(entries.contains("RED"))
	println(entries.contains(object {}))
	println(entries.indexOf(42))
	println(entries.contains(null))
	val boxes: List<Any?> = Boxes()
	println(boxes.contains("a"))
	println(boxes.indexOf(Color.RED))
	println(boxes.contains(boxes[0]))
}
