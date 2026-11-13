import ktc2.Cleaner
import ktc2.createCleaner

// `createCleaner` as in Kotlin/Native: the action runs when the cleaner ends. In C that is when
// the last reference goes; on the JVM whenever the collector gets to it, so this program is
// compared with Cleaners.expected.

class Handle(val name: String) {
	fun dispose() {
		println("dispose $name")
	}
}

// ==================
// MARK: A cleaner kept in a private property: no object is made for it
// ==================

class Wrapper(inName: String) {
	private val mHandle = Handle(inName)
	private val mCleaner = createCleaner(mHandle) { it.dispose() }

	fun name(): String = mHandle.name
}

/** The resource is a number, as a handle of a C library is. */
class Descriptor(inNumber: Int) {
	private val mCleaner = createCleaner(inNumber) { println("close $it") }
}

class Box<T>(inValue: T) {
	private val mCleaner = createCleaner(inValue) { println("unbox $it") }
}

open class Base(inName: String) {
	private val mBase = createCleaner("$inName.base") { println("clean $it") }
}

/** Its own cleaners first, in the order they are declared, then the one of its superclass. */
class Sub(inName: String) : Base(inName) {
	private val mFirst = createCleaner("$inName.first") { println("clean $it") }
	private val mSecond = createCleaner("$inName.second") { println("clean $it") }
}

/** Inherits the cleaner. */
class Heir(inName: String) : Base(inName)

fun folded() {
	val vWrapper = Wrapper("wrapped")
	println("made ${vWrapper.name()}")
	Descriptor(7)
	println("descriptor gone")
	Box("text")
	Box(3.5)
	val vAll = ArrayList<Any>()
	vAll += Sub("sub")
	vAll += Heir("heir")
	println("holding ${vAll.size}")
	vAll.clear()
	println("end of folded")
}

// ==================
// MARK: A cleaner that is an object
// ==================

/** Anyone can read the property: the cleaner has to exist. */
class Shared(inName: String) {
	val cleaner: Cleaner = createCleaner(Handle(inName)) { it.dispose() }
}

fun kept() {
	val vLocal = createCleaner(Handle("local")) { it.dispose() }
	println("local cleaner made: ${(vLocal as Any) is Cleaner}")
	val vShared = Shared("shared")
	val vAgain: Cleaner = vShared.cleaner
	val vList = ArrayList<Cleaner>()
	vList += createCleaner(Handle("listed"), Handle::dispose)
	vList += vAgain
	println("clearing")
	vList.clear()
	println("end of kept")
}

fun failing() {
	val vWrapper = Wrapper("unwound")
	println("throwing with ${vWrapper.name()}")
	throw IllegalStateException("boom")
}

val gForever = Wrapper("top-level")

fun main() {
	folded()
	println("after folded")
	kept()
	println("after kept")
	try {
		failing()
	} catch (inError: IllegalStateException) {
		println("caught ${inError.message}")
	}
	println("alive at the end: ${gForever.name()}")
}
