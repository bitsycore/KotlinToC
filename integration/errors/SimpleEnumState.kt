// What cannot be a number is refused in a `@SimpleEnum` class.

import ktc2.EnumValue
import ktc2.SimpleEnum

@SimpleEnum
enum class Mode(@EnumValue val code: Int) {
	OFF(0), ON(1) {
		override fun label(): String = "on!"
	},
	AUTO(1);

	var uses = 0

	open fun label(): String = name
}

@SimpleEnum
enum class Level(val weight: Int) { LOW(listOf(1).size), HIGH(2) }

fun main() {
	println(Mode.ON.label() + Mode.OFF.uses + Level.LOW.weight)
}
