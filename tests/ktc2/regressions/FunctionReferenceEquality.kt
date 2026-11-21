package ktc2test.regressions.functionreferenceequality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Two references to one function, holding equal values, are equal and hash alike, whichever expressions made them; and
// a function reference knows its name. Each was a new object compared by identity, and its name threw.

fun top(x: Int) = x
suspend fun suspendTop(x: Int) = x

data class Key(val id: Int) {
	fun twice() = id * 2
}

class Holder(val x: Int) {
	fun get() = x
	suspend fun suspendGet() = x
}

class FunctionReferenceEqualityTest {
	@Test
	fun unbound() {
		assertTrue(::top == ::top)
		assertEquals(::top.hashCode(), ::top.hashCode())
		assertTrue(Holder::get == Holder::get)
		assertFalse(::top == ::suspendTop)
	}

	@Test
	fun bound() {
		val vHolder = Holder(42)
		assertTrue(vHolder::get == vHolder::get)
		assertFalse(vHolder::get == Holder(42)::get)
		// A receiver that is equal without being the same object: equal references, as on the JVM.
		assertTrue(Key(1)::twice == Key(1)::twice)
		assertEquals(Key(1)::twice.hashCode(), Key(1)::twice.hashCode())
		assertFalse(Key(1)::twice == Key(2)::twice)
	}

	@Test
	fun suspendReferences() {
		val vHolder = Holder(7)
		assertTrue(::suspendTop == ::suspendTop)
		assertTrue(vHolder::suspendGet == vHolder::suspendGet)
		assertFalse(vHolder::suspendGet == Holder(7)::suspendGet)
	}

	@Test
	fun local() {
		fun local() = 1
		assertTrue(::local == ::local)
		assertEquals(::local.hashCode(), ::local.hashCode())
	}

	@Test
	fun names() {
		val vReference = ::top
		val vBound = Holder(1)::suspendGet
		val vNames = listOf(vReference.name, vBound.name, Holder::get.name)
		println(vNames)
		assertEquals(listOf("top", "suspendGet", "get"), vNames)
	}

	@Test
	fun inCollections() {
		val vSet = hashSetOf<Any>(::top, ::top, Holder::get)
		assertEquals(2, vSet.size)
	}
}
