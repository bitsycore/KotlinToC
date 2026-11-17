package objects

// Calls through interfaces and open classes that the compiler can tell the target of, and
// the ones it cannot: both must behave as on the JVM.

interface Speaker {
	fun speak(): String
	fun twice(): String = speak() + speak()
}

/** The only class of the program that implements [Solo]. */
interface Solo {
	fun value(): Int
}

class OnlyOne(private val mValue: Int) : Solo {
	override fun value(): Int = mValue
}

class Dog : Speaker {
	override fun speak(): String = "woof"
}

class Cat : Speaker {
	override fun speak(): String = "meow"
	override fun twice(): String = "purr"
}

open class Base(val name: String) {
	open fun describe(): String = "base $name"
	fun shout(): String = describe().uppercase()
}

class Derived(inName: String) : Base(inName) {
	override fun describe(): String = "derived $name"
}

/** Holds a speaker in a property declared as the interface. Only dogs are ever stored in [fixed]. */
class Kennel {
	val fixed: Speaker = Dog()
	var changing: Speaker = Dog()
	var maybe: Speaker? = null
	private val mItems: MutableList<String> = ArrayList()

	fun add(inItem: String) {
		mItems.add(inItem)
	}

	fun joined(): String {
		var vResult = ""
		for (vItem in mItems) vResult += vItem
		return vResult
	}
}

/** Called with one class only. */
fun onlyDogs(inSpeaker: Speaker): String = inSpeaker.speak() + "!"

/** Called with two classes. */
fun anySpeaker(inSpeaker: Speaker): String = inSpeaker.twice()

fun make(inFlag: Boolean): Speaker = if (inFlag) Dog() else Cat()

fun makeDog(): Speaker = Dog()

fun total(inList: List<Int>): Int {
	var vSum = 0
	for (vValue in inList) vSum += vValue
	return vSum
}

fun apply(inValue: Int, inOperation: (Int) -> Int): Int = inOperation(inValue)

class Failing : Solo {
	override fun value(): Int = throw IllegalStateException("no value")
}

interface Thrower {
	fun run(): Int
}

class Throws : Thrower {
	override fun run(): Int = listOf(1, 2, 3)[5]
}

fun main() {
	val vSolo: Solo = OnlyOne(7)
	println(vSolo.value())

	val vDog: Speaker = Dog()
	println(vDog.speak() + " " + vDog.twice())
	val vCat: Speaker = Cat()
	println(vCat.speak() + " " + vCat.twice())
	println(onlyDogs(Dog()) + onlyDogs(vDog))
	println(anySpeaker(Dog()) + " " + anySpeaker(Cat()))
	println(make(true).speak() + make(false).speak() + makeDog().twice())

	val vBase: Base = Derived("d")
	println(vBase.describe() + " / " + vBase.shout() + " / " + Base("b").shout())

	val vKennel = Kennel()
	println(vKennel.fixed.speak() + vKennel.changing.speak() + (vKennel.maybe?.speak() ?: "-"))
	vKennel.changing = Cat()
	vKennel.maybe = Cat()
	println(vKennel.fixed.speak() + vKennel.changing.speak() + (vKennel.maybe?.speak() ?: "-"))
	vKennel.add("a")
	vKennel.add("b")
	println(vKennel.joined())

	// A local that holds different classes over time.
	var vSpeaker: Speaker = Dog()
	var vSaid = vSpeaker.speak()
	vSpeaker = Cat()
	vSaid += vSpeaker.speak()
	println(vSaid)

	// Iterators that stay in the frame, over lists seen through their interfaces.
	val vNumbers = ArrayList<Int>()
	for (vI in 1..10) vNumbers.add(vI)
	println(total(vNumbers))
	val vView: List<Int> = vNumbers
	var vEvens = 0
	for (vNumber in vView) if (vNumber % 2 == 0) vEvens++
	println(vEvens)
	println(vNumbers.map { it * 2 }.filter { it > 10 }.sum())
	val vIterator = vNumbers.iterator()
	var vFirst = 0
	if (vIterator.hasNext()) vFirst = vIterator.next()
	println(vFirst)
	val vEmpty = ArrayList<String>().iterator()
	try {
		vEmpty.next()
	} catch (vError: NoSuchElementException) {
		println("no element")
	}

	// One lambda class behind the function type, then two.
	println(apply(4) { it * it })
	val vOperations = listOf<(Int) -> Int>({ it + 1 }, { it * 10 })
	println(vOperations.sumOf { it(5) })

	// What every object has, on classes, strings and numbers.
	val vThings = listOf<Any>(Dog(), "text", 42, 'c', 2.5, listOf(1))
	println(vThings.map { it.toString().length > 0 }.all { it })
	val vSequence: CharSequence = "sequence"
	println(vSequence.length.toString() + vSequence[2] + vSequence.subSequence(1, 4))
	val vComparables = listOf<Comparable<*>>("b", "a")
	println(vComparables.size)

	// A direct call still reports its exception.
	val vFailing: Solo = if (vNumbers.size > 1000) OnlyOne(1) else Failing()
	try {
		println(vFailing.value())
	} catch (vError: IllegalStateException) {
		println("failed: ${vError.message}")
	}
	val vThrower: Thrower = Throws()
	try {
		println(vThrower.run())
	} catch (vError: IndexOutOfBoundsException) {
		println("out of bounds")
	}
}
