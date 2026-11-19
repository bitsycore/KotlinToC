// A generic class with a field whose type is a class below it, through a generic class in between, as
// kotlinx.coroutines' AbstractSharedFlow holds a SubscriptionCountStateFlow, a SharedFlowImpl<Int> below it.

package ktc2test.generics.fieldofsubclasstype

import kotlin.test.Test

abstract class Slot<F>

abstract class Shared<S : Slot<*>> {
	private var mCount: CountFlow? = null
	protected var slots: Array<S?>? = null

	val count: CountFlow
		get() = mCount ?: CountFlow(slotCount()).also { mCount = it }

	protected abstract fun createSlots(size: Int): Array<S?>

	fun allocate(): Int {
		val vSlots = slots ?: createSlots(2).also { slots = it }
		count.increment(1)
		return vSlots.size
	}

	fun slotCount(): Int = slots?.count { it != null } ?: 0
}

class IntSlot : Slot<Flowing<*>>()

open class Flowing<T>(val first: T) : Shared<IntSlot>() {
	override fun createSlots(size: Int): Array<IntSlot?> = arrayOfNulls(size)

	override fun toString(): String = "Flowing($first)"
}

class CountFlow(initial: Int) : Flowing<Int>(initial) {
	var value = initial
		private set

	fun increment(delta: Int) {
		value += delta
	}
}

@Test
fun main() {
	val vFlow = Flowing("text")
	println(vFlow.allocate())
	println(vFlow.count.value)
	println(vFlow.allocate())
	println(vFlow.count.value)
	println(vFlow)
	val vCount = CountFlow(5)
	println(vCount.count.value)
	println(vCount.allocate())
	println(vCount)
}
