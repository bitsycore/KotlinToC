// C enums: as Kotlin enum classes when their constants are simply listed, as integers when
// they are given values, and as the .def file says when it says.

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import enums.*
import kotlinx.cinterop.*

fun describe(inColor: color_t): String = when (inColor) {
	color_t.COLOR_RED -> "warm"
	color_t.COLOR_GREEN -> "fresh"
	color_t.COLOR_BLUE -> "cold"
}

fun main() {
	// An enum class: entries, their C values, and back.
	println(color_t.entries.joinToString { it.name + "=" + it.value })
	println(color_t.byValue(2u))
	println(direction.WEST.value.toString() + " " + direction.valueOf("EAST").ordinal)

	// Through C functions, in both directions.
	var vColor = color_t.COLOR_RED
	for (vStep in 0 until 4) {
		print(color_name(vColor)?.toKString() + ":" + describe(vColor) + " ")
		vColor = next_color(vColor)
	}
	println()
	println(turn(direction.NORTH, 3))
	println(turn(turn(direction.SOUTH, 1), 2))

	// A field of a struct.
	memScoped {
		val vPixel = alloc<pixel>()
		vPixel.x = 3
		vPixel.color = color_t.COLOR_BLUE
		vPixel.facing = turn(direction.EAST, 1)
		println(vPixel.x.toString() + " " + vPixel.color + " " + vPixel.facing)
		vPixel.color = next_color(vPixel.color)
		println(vPixel.color)
	}

	// Given values, but asked for as an enum class: a second name for a value is the same entry.
	println(level.entries.joinToString { it.name + "=" + it.value })
	println(level.LEVEL_TOP == level.LEVEL_HIGH)
	println(raise(level.LEVEL_BELOW).toString() + " " + raise(level.LEVEL_LOW))

	// Integers: listed but asked for as such, and flags.
	val vPlain: plain_t = PLAIN_B
	println(vPlain.toString() + " " + other_plain(vPlain) + " " + other_plain(PLAIN_A))
	val vFlags = FLAG_READ or FLAG_WRITE
	println(vFlags.toString() + " " + (vFlags == FLAG_ALL) + " " + has_flag(FLAG_READ, FLAG_WRITE) + " " + has_flag(vFlags, FLAG_WRITE))

	// A function called from C sees the enum as the integer it is.
	println(apply(staticCFunction { vValue: UInt -> (vValue + 2u) % 3u }, color_t.COLOR_GREEN))

	// A value that is no entry.
	try {
		println(bad_color())
	} catch (vError: NotImplementedError) {
		println(vError.message)
	}
}
