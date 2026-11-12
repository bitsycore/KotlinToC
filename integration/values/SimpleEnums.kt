// `@SimpleEnum`: an enum whose values are numbers in C (a C `enum`), and an ordinary enum on
// the JVM. Everything here has to print the same on both.

package simple

import ktc2.EnumValue
import ktc2.SimpleEnum

/** The plain kind: the number is the ordinal. */
@SimpleEnum
enum class Direction { NORTH, EAST, SOUTH, WEST;

	fun turned(): Direction = when (this) {
		NORTH -> EAST
		EAST -> SOUTH
		SOUTH -> WEST
		WEST -> NORTH
	}

	val isVertical: Boolean get() = this == NORTH || this == SOUTH
}

/** The number is the code; the other values are looked up from it. */
@SimpleEnum
enum class Key(@EnumValue val code: Int, val label: String, val isLetter: Boolean = true) {
	A(4, "a"), B(5, "b"), RETURN(40, "return", false), ESCAPE(41, "escape", false), MINUS(-3, "-", false);

	fun describe(): String = "$name($code) '$label'" + if (isLetter) " letter" else ""

	companion object {
		fun ofCode(code: Int): Key? = values().firstOrNull { it.code == code }
	}
}

interface Scored {
	val score: Int
}

/** Implements an interface, and refers to another simple enum. */
@SimpleEnum
enum class Medal(val points: Long, val heading: Direction, val ratio: Double, val mark: Char) : Scored {
	GOLD(3L, Direction.NORTH, 1.0, 'G'), SILVER(2L, Direction.EAST, 0.5, 'S'), BRONZE(1L, Direction.SOUTH, 0.25, 'B');

	override val score: Int get() = (points * 10).toInt()
}

/** An ordinary enum next to them. */
enum class Plain { ONE, TWO }

class Player(val name: String, var medal: Medal, var key: Key?)

fun label(direction: Direction): String = when (direction) {
	Direction.NORTH -> "up"
	Direction.SOUTH -> "down"
	else -> "sideways"
}

fun <T> same(a: T, b: T): Boolean = a == b

fun describe(value: Any?): String = when (value) {
	null -> "nothing"
	is Direction -> "direction ${value.name} ${value.ordinal}"
	is Key -> "key ${value.label}"
	is Scored -> "scored ${value.score}"
	is Enum<*> -> "enum ${value.name}"
	else -> "other $value"
}

fun total(scored: List<Scored>): Int = scored.sumOf { it.score }

fun main() {
	// As numbers.
	var vDirection = Direction.NORTH
	for (vI in 0 until 6) {
		print("$vDirection ${vDirection.ordinal} ${label(vDirection)} ${vDirection.isVertical}; ")
		vDirection = vDirection.turned()
	}
	println()
	println(vDirection == Direction.SOUTH)
	println(vDirection != Direction.SOUTH)
	println(Direction.WEST.name + " " + Direction.WEST.toString().length + " " + Direction.EAST.compareTo(Direction.WEST) + " " + (Direction.EAST < Direction.SOUTH))

	// The values of the constructor.
	for (vKey in Key.values()) println(vKey.describe())
	println(Key.RETURN.code + Key.MINUS.code)
	println(Key.valueOf("ESCAPE").label)
	println(Key.ofCode(5)?.name + " " + Key.ofCode(6)?.name)
	try {
		Key.valueOf("NONE")
	} catch (vError: IllegalArgumentException) {
		println(vError.message)
	}
	for (vMedal in Medal.values()) println("${vMedal.name} ${vMedal.points} ${vMedal.heading} ${vMedal.ratio} ${vMedal.mark} ${vMedal.score} ${vMedal.heading.turned()}")

	// As objects.
	val vKeys = ArrayList<Key>()
	vKeys.add(Key.B)
	vKeys.add(Key.MINUS)
	vKeys.add(Key.A)
	println(vKeys)
	println(vKeys.sorted())
	println(vKeys.sortedBy { it.code })
	println(vKeys.contains(Key.A).toString() + " " + vKeys.contains(Key.ESCAPE) + " " + vKeys.indexOf(Key.MINUS))
	println(vKeys.map { it.label }.joinToString("+"))
	println(Direction.entries)
	println(Direction.entries.size.toString() + " " + Direction.entries[2] + " " + Direction.entries.indexOf(Direction.WEST))
	val vCounts = HashMap<Direction, Int>()
	for (vI in 0 until 10) {
		val vKey = Direction.values()[vI % 3]
		vCounts[vKey] = (vCounts[vKey] ?: 0) + 1
	}
	println("${vCounts[Direction.NORTH]} ${vCounts[Direction.EAST]} ${vCounts[Direction.SOUTH]} ${vCounts[Direction.WEST]}")
	val vSet = setOf(Medal.GOLD, Medal.BRONZE, Medal.GOLD)
	println("${vSet.size} ${Medal.SILVER in vSet} ${Medal.BRONZE in vSet}")

	// Nullable, Any, generic.
	var vMaybe: Key? = null
	println(describe(vMaybe))
	vMaybe = Key.A
	println(describe(vMaybe) + " " + (vMaybe == Key.A) + " " + (vMaybe == Key.B) + " " + vMaybe.code)
	val vAny: Any = Direction.EAST
	println(describe(vAny) + ", " + describe(Medal.SILVER) + ", " + describe(Plain.TWO) + ", " + describe(7))
	println(same(Key.A, Key.A).toString() + " " + same(Key.A, Key.B) + " " + same<Any>(Key.A, Direction.NORTH))
	val vFirst: Any = Key.RETURN
	val vSecond: Any = Key.RETURN
	println((vFirst === vSecond).toString() + " " + (vFirst == vSecond) + " " + (vFirst.hashCode() == vSecond.hashCode()) + " " + vFirst)
	println((vAny as Direction).turned())
	println(total(listOf(Medal.GOLD, Medal.BRONZE)))

	// In an object, in an array.
	val vPlayer = Player("ada", Medal.SILVER, null)
	vPlayer.medal = Medal.GOLD
	vPlayer.key = Key.ESCAPE
	println("${vPlayer.name} ${vPlayer.medal} ${vPlayer.medal.points} ${vPlayer.key} ${vPlayer.key?.code}")
	val vRow = arrayOf(Direction.WEST, Direction.NORTH)
	vRow[1] = vRow[0].turned()
	println(vRow.joinToString(" ") { it.name.lowercase() })
	val vGrid = Array(4) { Direction.values()[3 - it] }
	println(vGrid.contentToString())
	println(enumValues<Direction>().size.toString() + " " + enumValueOf<Medal>("BRONZE").points)
}
