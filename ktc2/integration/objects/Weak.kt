package objects

import ktc2.Weak
import ktc2.WeakRef

/** A tree whose children point back at their parent without owning it. */
class TreeNode(val name: String) {
	@Weak
	var parent: TreeNode? = null
	val children = ArrayList<TreeNode>()

	fun add(inChild: TreeNode): TreeNode {
		inChild.parent = this
		children.add(inChild)
		return inChild
	}

	fun path(): String {
		val vParent = parent
		return if (vParent == null) name else "${vParent.path()}/$name"
	}

	fun depth(): Int = (parent?.depth() ?: -1) + 1
}

/** Two objects that know each other: one side owns, the other observes. */
class Owner(val name: String) {
	var pet: Pet? = null
}

class Pet(val name: String) {
	@Weak
	var owner: Owner? = null

	fun describe(): String = "$name belongs to ${owner?.name ?: "nobody"}"
}

class Listener(val id: Int) {
	var calls = 0

	fun notify(inEvent: String) {
		calls++
		println("listener $id got $inEvent")
	}
}

/** Observers are held weakly: registering does not keep a listener alive. */
class EventSource {
	private val mListeners = ArrayList<WeakRef<Listener>>()

	fun register(inListener: Listener) {
		mListeners.add(WeakRef(inListener))
	}

	fun fire(inEvent: String): Int {
		var vReached = 0
		for (vRef in mListeners) {
			val vListener = vRef.get() ?: continue
			vListener.notify(inEvent)
			vReached++
		}
		return vReached
	}
}

fun buildTree(): TreeNode {
	val vRoot = TreeNode("root")
	val vUsr = vRoot.add(TreeNode("usr"))
	vUsr.add(TreeNode("bin"))
	val vLocal = vUsr.add(TreeNode("local"))
	vLocal.add(TreeNode("share"))
	vRoot.add(TreeNode("etc"))
	return vRoot
}

fun printTree(inNode: TreeNode) {
	println("${inNode.depth()} ${inNode.path()}")
	for (vChild in inNode.children) printTree(vChild)
}

fun main() {
	val vRoot = buildTree()
	printTree(vRoot)
	println(vRoot.parent == null)
	val vShare = vRoot.children[0].children[1].children[0]
	println(vShare.parent?.parent?.name)

	// Moving a node changes what its weak parent refers to.
	val vEtc = vRoot.children[1]
	vEtc.add(vShare)
	println(vShare.path())
	vShare.parent = null
	println(vShare.path())

	val vOwner = Owner("Ada")
	val vPet = Pet("Rex")
	println(vPet.describe())
	vOwner.pet = vPet
	vPet.owner = vOwner
	println(vPet.describe())
	println(vOwner.pet?.owner === vOwner)

	val vSource = EventSource()
	val vFirst = Listener(1)
	val vSecond = Listener(2)
	vSource.register(vFirst)
	vSource.register(vSecond)
	vSource.register(vFirst)
	println(vSource.fire("start"))
	println(vSource.fire("stop"))
	println("${vFirst.calls} ${vSecond.calls}")

	val vText = WeakRef("literal")
	println(vText.get())
	val vSame = WeakRef(vFirst)
	println(vSame.get() === vFirst)
	println("done")
}
