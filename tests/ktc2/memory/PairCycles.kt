// Two objects that hold each other through their own fields are freed when nothing else holds either: a star of
// them around one object too, from whichever side the last other reference goes. The leak check at the end has
// nothing to report; what each prints shows that none was freed while still in use.

package ktc2test.memory.paircycles

import kotlin.test.Test

class Node(val name: String) {
	val child = Child()
	val action = { "action of $name" }

	inner class Child {
		val owner: Node get() = this@Node
		fun describe() = "child of $name"
	}
}

class Holder(val label: String) {
	var partner: Partner? = null
}

class Partner(val holder: Holder) {
	fun describe() = "partner of ${holder.label}"
}

fun make(inName: String): Node = Node(inName)

fun pair(inLabel: String): Partner {
	val vHolder = Holder(inLabel)
	val vPartner = Partner(vHolder)
	vHolder.partner = vPartner
	return vPartner
}

val gKept = ArrayList<Any>()

@Test
fun main() {
	// The node goes last.
	val vNode = make("one")
	println("${vNode.child.describe()} ${vNode.action()}")
	// Its child goes last: kept after the node's last other reference went.
	val vChild = make("two").child
	println("${vChild.describe()} ${vChild.owner.name}")
	// Its lambda goes last.
	val vAction = make("three").action
	println(vAction())
	// Kept in a list, then let go.
	gKept += make("four").child
	gKept += make("five")
	println((gKept[0] as Node.Child).describe())
	gKept.clear()
	// A pair made through a var, given up from either side.
	val vPartner = pair("a")
	println(vPartner.describe())
	val vHolder = pair("b").holder
	println("${vHolder.label} ${vHolder.partner?.describe()}")
	// The pair broken by the program before it is let go.
	val vBroken = pair("c")
	vBroken.holder.partner = null
	println(vBroken.describe())
	// Many, in a loop.
	var vTotal = 0
	for (vIndex in 0 until 1000) {
		val vMade = make("n$vIndex")
		vTotal += vMade.child.describe().length + pair("p$vIndex").describe().length
	}
	println(vTotal)
}
