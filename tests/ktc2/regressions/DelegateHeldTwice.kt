package ktc2test.regressions.delegateheldtwice

import kotlin.properties.Delegates
import kotlin.test.Test
import kotlin.test.assertEquals

// An observable delegate kept in a property of its own and used by another (`var b by bDelegate`): the object holds it
// twice, and its handler holds the object. The pair was left at the end: its check wanted the delegate held once.

class Observed {
	var total = 0
	val bDelegate = Delegates.observable(1) { _, _, new -> total += new }
	var b: Int by bDelegate
}

class ObservedOnce {
	var total = 0
	var b: Int by Delegates.observable(1) { _, _, new -> total += new }
}

class DelegateHeldTwiceTest {
	@Test
	fun heldTwice() {
		val vObserved = Observed()
		vObserved.b = 4
		vObserved.b = 5
		assertEquals(9, vObserved.total)
		assertEquals(5, vObserved.b)
	}

	@Test
	fun heldOnce() {
		val vObserved = ObservedOnce()
		vObserved.b = 6
		assertEquals(6, vObserved.total)
	}
}
