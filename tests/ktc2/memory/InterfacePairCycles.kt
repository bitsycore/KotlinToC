// Two objects that hold each other through fields typed as interfaces, as a serializer and its descriptor of
// kotlinx.serialization do: the serializer makes its descriptor and hands it its own `this`, which the
// descriptor keeps as an interface. And a pair whose partner may be of several classes (a class and its
// subclass). The runtime frees each pair when nothing else holds either: the leak check at the end has nothing
// to report, and what each prints shows that none was freed while still in use.

package ktc2test.memory.interfacepaircycles

import kotlin.test.Test

interface Described {
	val description: Description
	fun name(): String
}

interface Description {
	val described: Described
	fun text(): String
}

open class PlainDescription(private val label: String, override val described: Described) : Description {
	override fun text(): String = "$label of ${described.name()}"
}

class LongDescription(label: String, described: Described) : PlainDescription(label, described) {
	override fun text(): String = "long " + super.text()
}

class Serializer(private val name: String, inLong: Boolean) : Described {
	override val description: Description = if (inLong) LongDescription("description", this) else PlainDescription("description", this)
	override fun name(): String = name
}

/** A pair through two `var`s typed as interfaces. */
interface Peer {
	var other: Peer?
	fun name(): String
}

class Left : Peer {
	override var other: Peer? = null
	override fun name() = "left"
}

class Right : Peer {
	override var other: Peer? = null
	override fun name() = "right"
}

val gKept = ArrayList<Any>()

@Test
fun main() {
	// The serializer goes last.
	val vFirst = Serializer("first", false)
	println(vFirst.description.text())
	// Its description goes last, of the subclass.
	val vDescription = Serializer("second", true).description
	println(vDescription.text())
	println(vDescription.described.name())
	// Kept in a list, then let go.
	gKept += Serializer("third", true)
	gKept += Serializer("fourth", false).description
	println((gKept[0] as Described).description.text())
	println((gKept[1] as Description).text())
	gKept.clear()
	// Several in a loop.
	var vCount = 0
	for (vIndex in 0 until 1000) vCount += Serializer("n$vIndex", vIndex % 2 == 0).description.text().length
	println(vCount)
	// Two objects that refer to each other through interface-typed vars, set from outside.
	for (vIndex in 0 until 3) {
		val vLeft = Left()
		val vRight = Right()
		vLeft.other = vRight
		vRight.other = vLeft
		println("${vLeft.other?.name()} ${vRight.other?.name()} ${vLeft.other?.other?.name()}")
		if (vIndex == 1) gKept += vRight
	}
	gKept.clear()
	println("done")
}
