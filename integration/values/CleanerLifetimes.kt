import ktc2.WeakRef
import ktc2.arena
import ktc2.createCleaner

// When an object ends, seen through its cleaner: in C the action runs when the last reference
// to the object goes. On the JVM it runs whenever the collector gets to it, so this program is
// compared with CleanerLifetimes.expected.

/** Prints when it ends. */
open class Res(val name: String) {
	private val mCleaner = createCleaner(name) { println("end $it") }
}

/** Inherits the cleaner. */
class Plain(inName: String) : Res(inName)

/** Adds one: its own runs first. */
class Derived(inName: String, inExtra: String) : Res(inName) {
	private val mExtra = createCleaner(inExtra) { println("end derived $it") }
}

/** Its properties are whole while the action runs, and given up after it. */
class Parent(val name: String) {
	val child = Res("$name.child")
	val notes = ArrayList<String>()
	private val mCleaner = createCleaner(notes) { println("end parent with $it") }
}

class Generic<T>(inValue: T) {
	private val mCleaner = createCleaner(inValue) { println("end generic $it") }
}

interface Named {
	val label: String
}

class NamedRes(override val label: String) : Named {
	private val mCleaner = createCleaner(label) { println("end named $it") }
}

// ==================
// MARK: Lifetime
// ==================

fun scope() {
	val vRes = Res("local")
	println("in scope with ${vRes.name}")
}

fun make(inName: String): Res = Res(inName)

fun reassign() {
	var vRes = make("first")
	println("made ${vRes.name}")
	vRes = make("second")
	println("made ${vRes.name}")
}

fun inherited() {
	val vAll: List<Res> = listOf(Plain("plain"), Derived("base", "extra"))
	println("holding ${vAll.size}")
}

fun parent() {
	val vParent = Parent("parent")
	vParent.notes += "a"
	vParent.notes += "b"
	println("parent made")
}

fun collections() {
	val vList = ArrayList<Res>()
	for (vIndex in 0 until 3) vList += Res("item$vIndex")
	println("removing")
	vList.removeAt(1)
	println("clearing")
	vList.clear()
	println("cleared")
	val vMap = HashMap<String, Res>()
	vMap["k"] = Res("old")
	vMap["k"] = Res("new")
	println("replaced")
	val vArray = arrayOfNulls<Res>(2)
	vArray[0] = Res("slot")
	vArray[0] = null
	println("array emptied")
}

fun seenAs() {
	val vAny: Any = Generic(42)
	val vNamed: Named = NamedRes("iface")
	println("as Any: ${vAny !is Res}, as Named: ${vNamed.label}")
	val vText = Generic("text")
	val vHolder: () -> Unit = { println("captured generic is a Res: ${(vText as Any) is Res}") }
	vHolder()
}

// ==================
// MARK: Temporaries and variables
// ==================

fun consume(inRes: Res): Int = inRes.name.length

/** What no variable holds ends with its statement; a variable keeps its object to the end of its block. */
fun temporaries() {
	consume(Res("argument"))
	println("after the call")
	Res("statement")
	println("after the statement")
	val vGuard = Res("variable")
	println("the variable is alive: ${vGuard.name.isNotEmpty()}")
	for (vRound in 0 until 2) {
		consume(Res("round$vRound"))
		println("end of round $vRound")
	}
	try {
		consume(Res("in try"))
		println("end of try")
	} finally {
		println("finally")
	}
	if (consume(Res("condition")) > 0) println("in the branch")
	println("end of the block")
}

// ==================
// MARK: Exceptions
// ==================

fun failing() {
	val vRes = Res("unwound")
	println("throwing with ${vRes.name}")
	throw IllegalStateException("boom")
}

/** Its action calls code that throws, and catches it, while another exception may be in flight. */
class Careful(inName: String) {
	private val mCleaner = createCleaner(inName) {
		val vWords = ArrayList<String>()
		try {
			vWords += it
			vWords[5]
		} catch (inError: IndexOutOfBoundsException) {
			vWords += "caught"
		}
		println("end careful $vWords")
	}
}

fun failingCareful() {
	val vCareful = Careful("careful")
	println("made ${vCareful.hashCode() != 0 || true}")
	throw IllegalArgumentException("bang")
}

// ==================
// MARK: Weak references
// ==================

var gWatched: WeakRef<Watched>? = null

class Watched {
	// A weak reference no longer finds an object that is ending.
	private val mCleaner = createCleaner("watched") { println("end $it, weak reference finds it: ${gWatched?.get() != null}") }
}

fun weak() {
	val vWatched = Watched()
	gWatched = WeakRef(vWatched)
	println("weak reference finds it: ${gWatched?.get() != null}")
}

// ==================
// MARK: Code that runs under a reference being given up
// ==================

class Label(val text: String)

class Holder {
	var item: Label? = Label("kept")
	var other: Label? = Label("other")
}

/** Overwrites, when it ends, a place something else may be reading from. */
class Killer(private val mHolder: Holder) {
	private val mCleaner = createCleaner(mHolder) {
		it.item = Label("replaced")
		it.other = null
	}

	fun touch(): Int = 1
}

fun dropped(inKiller: Killer): Int = inKiller.touch()

fun underBlock(inHolder: Holder, inFlag: Boolean) {
	val vItem = inHolder.item!!
	if (inFlag) {
		val vKiller = Killer(inHolder)
		vKiller.touch()
	}
	println("block: ${vItem.text} then ${inHolder.item?.text}")
}

fun underAssignment(inHolder: Holder) {
	val vItem = inHolder.item!!
	var vKiller: Killer? = Killer(inHolder)
	println("assignment: ${vKiller != null}")
	vKiller = null
	println("assignment: ${vItem.text} then ${inHolder.item?.text} $vKiller")
}

fun underCall(inHolder: Holder) {
	val vItem = inHolder.item!!
	val vOther = inHolder.other
	dropped(Killer(inHolder))
	val vList = ArrayList<Killer>()
	vList += Killer(inHolder)
	vList.clear()
	println("call: ${vItem.text} and ${vOther?.text} then ${inHolder.item?.text} and ${inHolder.other?.text}")
}

class Slot {
	var killer: Killer? = null
}

fun underField(inHolder: Holder, inSlot: Slot) {
	inSlot.killer = Killer(inHolder)
	val vItem = inHolder.item!!
	inSlot.killer = null
	println("field: ${vItem.text} then ${inHolder.item?.text}")
}

// ==================
// MARK: What is alive at the end
// ==================

val gForever = Res("top-level")

object Registry {
	val res = Res("object")
}

fun main() {
	scope()
	println("after scope")
	reassign()
	println("after reassign")
	inherited()
	println("after inherited")
	parent()
	println("after parent")
	collections()
	seenAs()
	println("after seenAs")
	temporaries()
	println("after temporaries")
	try {
		failing()
	} catch (inError: IllegalStateException) {
		println("caught ${inError.message}")
	}
	try {
		failingCareful()
	} catch (inError: IllegalArgumentException) {
		println("caught ${inError.message}")
	}
	weak()
	println("after weak: ${gWatched?.get() != null}")
	underBlock(Holder(), true)
	underAssignment(Holder())
	underCall(Holder())
	underField(Holder(), Slot())
	val vTotal = arena(4096) {
		val vInArena = Res("arena")
		vInArena.name.length
	}
	println("after arena $vTotal")
	println("alive at the end: ${gForever.name}, ${Registry.res.name}")
}
