import ktc2.Weak

// `@Weak inner class`: the inner object's reference to its outer one does not own it. Here the outer object is
// held by its inner one through a list, a cycle ktc2 does not free by itself (three objects): with @Weak the leak
// check has nothing to report. The JVM ignores the annotation, so this is compared with WeakInner.expected.

class Registry(val name: String) {
	val entries = ArrayList<Any>()

	@Weak
	inner class Entry(val label: String) {
		fun describe() = "$label in $name"
	}

	fun add(inLabel: String): Entry = Entry(inLabel).also { entries += it }
}

/** An entry whose registry is given up when this returns. */
fun leftBehind(): Registry.Entry {
	val vShort = Registry("short")
	val vEntry = vShort.add("left")
	println(vEntry.describe())
	return vEntry
}

fun main() {
	val vRegistry = Registry("main")
	val vFirst = vRegistry.add("first")
	vRegistry.add("second")
	println(vFirst.describe())
	println((vRegistry.entries[1] as Registry.Entry).describe())
	val vKeep: Registry.Entry? = leftBehind()
	// Its outer object is gone: using it is a NullPointerException, where the JVM would have kept it.
	try {
		println(vKeep!!.describe())
	} catch (inError: NullPointerException) {
		println("outer object gone")
	}
	println(vKeep!!.label)
}
