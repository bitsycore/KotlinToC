// An unchecked cast of a null to a type parameter that is a number here, as generic code does with a slot it has
// emptied (kotlinx.coroutines' channel segments): erased generics check nothing, and the value is never used.

package ktc2test.generics.uncheckednullcast

import kotlin.test.Test

class Slots<E>(inSize: Int) {
	private val mData = arrayOfNulls<Any?>(inSize)

	fun put(inIndex: Int, inValue: E) {
		mData[inIndex] = inValue
	}

	@Suppress("UNCHECKED_CAST")
	fun get(inIndex: Int): E = mData[inIndex] as E

	fun clear(inIndex: Int) {
		mData[inIndex] = null
	}

	/** Reads the slot whatever it holds, and uses it only when [inUse] says so. */
	fun describe(inIndex: Int, inUse: Boolean): String {
		val vElement = get(inIndex)
		return if (inUse) "slot $inIndex: $vElement" else "slot $inIndex read"
	}
}

@Test
fun main() {
	val vInts = Slots<Int>(3)
	vInts.put(0, 7)
	println(vInts.describe(0, true))
	vInts.clear(0)
	println(vInts.describe(0, false))
	println(vInts.describe(2, false))
	val vLongs = Slots<Long>(2)
	println(vLongs.describe(1, false))
	val vDoubles = Slots<Double>(1)
	vDoubles.put(0, 1.5)
	println(vDoubles.describe(0, true))
	val vStrings = Slots<String>(1)
	println(vStrings.describe(0, true))
}
