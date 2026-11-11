package stdlib

import kotlin.math.*
import kotlin.random.Random

enum class Planet(val mass: Double) {
	MERCURY(0.33), VENUS(4.87), EARTH(5.97);

	fun heavierThan(inOther: Planet): Boolean = mass > inOther.mass
}

fun kindOf(inValue: Any?): String = when (inValue) {
	is IntArray -> "ints ${inValue.size}"
	is DoubleArray -> "doubles ${inValue.size}"
	is ByteArray -> "bytes ${inValue.size}"
	is Array<*> -> "objects ${inValue.size}"
	is String -> "string"
	null -> "null"
	else -> "other"
}

fun main() {
	// kotlin.math
	println(sqrt(2.0))
	println(sin(PI / 6))
	println(cos(0.0))
	println(atan2(1.0, 1.0))
	println(exp(1.0) == E)
	println(ln(E))
	println(log10(1000.0))
	println(log2(8.0))
	println(log(81.0, 3.0))
	println(2.0.pow(10))
	println(2.0.pow(0.5))
	println(hypot(3.0, 4.0))
	println(cbrt(27.0))
	println("${floor(-1.5)} ${ceil(-1.5)} ${truncate(-1.5)} ${round(2.5)} ${round(3.5)} ${round(-2.5)}")
	println("${abs(-3.25)} ${abs(-7)} ${abs(-7L)}")
	println("${min(1.5, -2.0)} ${max(3, 9)} ${max(2L, 1L)} ${min(0.0, -0.0)}")
	println("${sign(-4.0)} ${sign(0.0)} ${(-5).sign} ${7.5.sign}")
	println("${2.5.roundToInt()} ${3.5.roundToInt()} ${(-2.5).roundToInt()} ${1e30.roundToInt()} ${2.4f.roundToInt()} ${7.6.roundToLong()}")
	println("${(-4.5).absoluteValue} ${(-4).absoluteValue} ${3.0.withSign(-1.0)}")
	println(sqrt(-1.0).isNaN())
	println(sqrt(16.0f))
	println(sin(1.0f))
	println(1.0.nextUp() > 1.0)
	println(tanh(0.5))
	println(asin(1.0) * 2)

	// Bit operations
	println("${255.countOneBits()} ${1.countLeadingZeroBits()} ${8.countTrailingZeroBits()} ${0.countLeadingZeroBits()}")
	println("${100.takeHighestOneBit()} ${100.takeLowestOneBit()} ${1.rotateLeft(31)} ${1.rotateRight(1)}")
	println("${(1L shl 40).countTrailingZeroBits()} ${(-1L).countOneBits()} ${5L.rotateLeft(62)}")

	// kotlin.random: a seeded generator gives the same numbers everywhere.
	val vRandom = Random(42)
	println("${vRandom.nextInt()} ${vRandom.nextInt(100)} ${vRandom.nextInt(5, 10)} ${vRandom.nextLong()}")
	println(vRandom.nextDouble())
	println(vRandom.nextBoolean())
	println(vRandom.nextBytes(4).contentToString())
	val vShuffled = (1..8).toMutableList()
	vShuffled.shuffle(Random(7))
	println(vShuffled)
	println(listOf("a", "b", "c").random(Random(1)))
	val vDefault = Random.nextInt(10)
	println(vDefault in 0..9)
	println(Random.nextDouble() < 1.0)

	// Enum entries
	println(Planet.entries)
	println(Planet.entries.size)
	println(Planet.entries.map { it.mass }.sum())
	println(Planet.entries.first { it.heavierThan(Planet.MERCURY) })
	println(Planet.entries.indexOf(Planet.EARTH))

	// Type tests on arrays
	println(kindOf(intArrayOf(1, 2)))
	println(kindOf(doubleArrayOf(1.0)))
	println(kindOf(ByteArray(3)))
	println(kindOf(arrayOf("a")))
	println(kindOf("s"))
	println(kindOf(null))
	println(kindOf(3))
	val vAny: Any = longArrayOf(1L)
	println(vAny is LongArray)
	println(vAny is IntArray)
	println((vAny as? IntArray) == null)
	println("done")
}
