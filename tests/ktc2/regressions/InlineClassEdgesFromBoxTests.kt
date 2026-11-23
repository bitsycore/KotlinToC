// What the box tests of `inlineClasses` found:
// - a value class over a type parameter bounded by String (`value class S<T : String>(val x: T)`) passed its value,
//   an Any there, to the String functions without a cast: its hashCode did not compile;
// - a value class boxed only by the bridge of a lambda had no type id: its box class was not made instantiated;
// - a `return` in a value class's secondary constructor returned nothing from a function that returns the value;
// - `super<IFoo>.foo()` in a value class called the class's own `foo`, itself;
// - `contains` of a value class that is a `Collection<Z>`, asked about any object through the interface, read that
//   object as a Z instead of answering false as Kotlin's bridge does;
// - `1u .. break` gave a value of type void to the range;
// - `(null as IC?).toString()` read the value out of a literal NULL in C, in the branch that never runs;
// - `w?.w?.s` on a null `w` built the box of the inner value before the test of null: a crash;
// - `x == v` with an object `x` and a value class `v` called v's equals, where Kotlin calls x's.

package ktc2test.regressions.inlineclassedgesfromboxtests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@JvmInline
value class Named<T : String>(val name: T)

@Test
fun valueOverBoundedTypeParameter() {
	val vA = Named("abc")
	assertEquals("abc".hashCode(), vA.hashCode())
	assertEquals(true, vA == Named("abc"))
	assertTrue(vA.name < "abd")
	assertEquals(3, vA.name.length)
}

@JvmInline
value class Meters(val value: Int)

fun <T> make(inBlock: () -> T): T = inBlock()

@Test
fun valueReturnedThroughLambda() {
	val vMade: Any? = make { Meters(5) }
	assertEquals(Meters(5), vMade)
	val vList = listOf(1, 2).map { Meters(it) }
	assertEquals(listOf(Meters(1), Meters(2)), vList)
}

@JvmInline
value class Code(val text: String) {
	constructor(inNumber: Int) : this("n$inNumber") {
		if (inNumber == 1) return
		if (inNumber < 0) return Unit
	}
}

@Test
fun returnInSecondaryConstructor() {
	assertEquals("n1", Code(1).text)
	assertEquals("n-2", Code(-2).text)
	assertEquals("n7", Code(7).text)
}

interface Greeter {
	fun greet(): String = "K"
}

@JvmInline
value class Greeting(val start: Int) : Greeter {
	override fun greet(): String = "O" + super<Greeter>.greet()
}

@Test
fun superCallInValueClass() {
	assertEquals("OK", Greeting(1).greet())
	val vGreeter: Greeter = Greeting(2)
	assertEquals("OK", vGreeter.greet())
}

@JvmInline
value class Z(val x: Int)

@JvmInline
value class ZArray(val storage: IntArray) : Collection<Z> {
	override val size: Int get() = storage.size

	override fun contains(element: Z): Boolean = storage.contains(element.x)

	override fun containsAll(elements: Collection<Z>): Boolean = elements.all { contains(it) }

	override fun isEmpty(): Boolean = storage.isEmpty()

	override fun iterator(): Iterator<Z> = storage.map { Z(it) }.iterator()
}

@Test
fun collectionValueAskedAboutAnything() {
	val vZs = ZArray(IntArray(3))
	assertTrue(vZs.contains(Z(0)))
	assertFalse(vZs.contains(Any() as Any))
	val vAsCollection: Collection<Any?> = vZs
	assertFalse(vAsCollection.contains("zero"))
	assertFalse(vAsCollection.contains(null))
}

@Test
fun unsignedBoundThatJumps() {
	var vRounds = 0
	for (i in 1..2) {
		vRounds++
		for (j in 1u..break) {
		}
	}
	assertEquals(1, vRounds)
}

@JvmInline
value class Label(val text: String)

fun Label?.shown(): String = toString()

@Test
fun nullValueClassAsText() {
	assertEquals("null", (null as Label?).shown())
	assertEquals("null", (null as Label?).toString())
	assertEquals("Label(text=a)", Label("a").shown())
}

@JvmInline
value class Wrap(val s: String)

@JvmInline
value class WrapWrap(val w: Wrap)

fun innerText(inValue: WrapWrap?): () -> String = { inValue?.w?.s ?: "null" }

@Test
fun safeCallThroughNestedValueClasses() {
	assertEquals("nested", innerText(WrapWrap(Wrap("nested")))())
	assertEquals("null", innerText(null)())
	val vNone: WrapWrap? = null
	assertEquals(null, vNone?.w)
}

class AlwaysEqual {
	override fun equals(other: Any?): Boolean = true

	override fun hashCode(): Int = 0
}

@Test
fun objectOnTheLeftAnswersEquality() {
	val vObject: Any? = AlwaysEqual()
	val vValue = Wrap("")
	assertTrue(vObject == vValue)
	assertFalse(vValue == vObject)
	val vNothing: Any? = null
	assertFalse(vNothing == vValue)
	val vSame: Any? = Wrap("a")
	assertTrue(vSame == Wrap("a"))
	assertFalse(vSame == Wrap("b"))
}
