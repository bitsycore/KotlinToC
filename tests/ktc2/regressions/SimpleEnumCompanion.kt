// The companion object of an enum starts the enum's entries first, as the JVM's class initializer does. For a
// `@SimpleEnum` that made the importer build its entries as objects, through a constructor that a C `enum` does not
// have: the compilation crashed (an index out of bounds in `callFunction`). Its entries are constants: nothing to start.

package ktc2test.regressions.simpleenumcompanion

import kotlin.test.Test
import kotlin.test.assertEquals
import ktc2.EnumValue
import ktc2.SimpleEnum

@SimpleEnum
enum class Signal(@EnumValue val code: UInt) {
	NONE(0u), STOP(15u), KILL(9u);

	companion object {
		val kDefault = STOP

		fun of(inCode: UInt): Signal = ktc2.enumOf<Signal>(inCode) { it.code } ?: NONE
	}
}

@Test
fun simpleEnumCompanion() {
	assertEquals(Signal.KILL, Signal.of(9u))
	assertEquals(Signal.NONE, Signal.of(1u))
	assertEquals(Signal.STOP, Signal.kDefault)
	println(Signal.of(15u))
}
