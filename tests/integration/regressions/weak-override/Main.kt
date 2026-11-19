// A property a module makes weak (`weak` in module.ktc2.toml) that overrides a property of an interface or of a
// superclass (Kotlin/Native's test runner, `TestCase.suite`): read through what it overrides, by its default getter,
// it gives the object, not its weak cell.

interface Part {
	val owner: Owner
	val name: String
}

abstract class BasePart(open val holder: Owner)

class Owner(val title: String) {
	val parts = ArrayList<Part>()
}

class Leaf(override val owner: Owner, override val name: String) : Part

class Branch(override val holder: Owner) : BasePart(holder)

fun describe(inPart: Part): String = "${inPart.owner.title}.${inPart.name}"

fun holderOf(inPart: BasePart): String = inPart.holder.title

fun main() {
	val vOwner = Owner("tree")
	val vLeaf = Leaf(vOwner, "leaf")
	vOwner.parts += vLeaf
	println(describe(vLeaf))
	println(vLeaf.owner.title)
	println(vOwner.parts.joinToString { describe(it) })
	val vBranch = Branch(vOwner)
	println(holderOf(vBranch))
	println(vBranch.holder.title)
}
