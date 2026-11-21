package ktc2test.regressions.unittypedcall

import kotlin.test.Test
import kotlin.test.assertEquals

// A call whose type is Unit gives the Unit object, whatever the function behind it returned: a function value cast to
// a type that returns Unit gave what the function returned.

object Dummy

fun plain(): Dummy = Dummy

class UnitTypedCallTest {
	@Test
	fun referenceCastToUnit() {
		val vCast = ::plain as () -> Unit
		val vResult: Any? = vCast()
		assertEquals<Any?>(Unit, vResult)
	}

	@Test
	@Suppress("UNCHECKED_CAST")
	fun lambdaCastToUnit() {
		val vGeneric: () -> Any? = { Dummy }
		val vUnit = vGeneric as () -> Unit
		val vResult: Any = vUnit()
		assertEquals<Any>(Unit, vResult)
	}

	@Test
	fun unitFunctionValue() {
		var vCalls = 0
		val vCount: () -> Unit = { vCalls++ }
		val vResult: Any = vCount()
		assertEquals<Any>(Unit, vResult)
		assertEquals(1, vCalls)
	}
}
