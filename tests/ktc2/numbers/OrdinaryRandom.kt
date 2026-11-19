package ktc2test.numbers.ordinaryrandom

import kotlin.test.Test
import kotlin.random.*

// `Random` as ordinary code uses it: a seeded generator gives the same numbers as on the JVM and
// Kotlin/Native (the library's own algorithm), in ranges, as bits, bytes, doubles and booleans;
// shuffling and picking with it; and the default generator, of which only bounds are printed.

class Dice(seed: Int) {
	private val random = Random(seed)
	fun roll() = random.nextInt(1, 7)
}

@Test
fun main() {
	val random = Random(42)
	println(List(5) { random.nextInt() })
	println(List(5) { random.nextInt(100) })
	println(List(5) { random.nextInt(-10, 10) })
	println(List(3) { random.nextLong() })
	println(List(3) { random.nextLong(1_000_000_000_000L) })
	println(List(3) { random.nextDouble() })
	println(List(3) { random.nextDouble(5.0, 6.0) })
	println(List(3) { random.nextFloat() })
	println(List(8) { random.nextBoolean() })
	println(List(4) { random.nextBits(5) })
	println(random.nextBytes(6).toList())
	println(random.nextBytes(ByteArray(8), 2, 5).toList())
	println(List(3) { random.nextUInt() })
	println(List(3) { random.nextULong(10uL..20uL) })
	println(random.nextUBytes(4).toList())
	println(List(5) { random.nextInt(0..2) })
	println(List(3) { random.nextLong(5L..7L) })

	val seeded = Random(7L)
	println((1..10).shuffled(seeded))
	println(listOf("a", "b", "c", "d").random(seeded))
	println(listOf("x", "y").randomOrNull(seeded))
	println(emptyList<Int>().randomOrNull(seeded))
	println((1..100).random(seeded))
	println('a'..'z' to ('a'..'z').random(seeded))
	println(intArrayOf(1, 2, 3, 4).also { it.shuffle(seeded) }.toList())
	println(mutableListOf(1, 2, 3, 4, 5).also { it.shuffle(seeded) })
	println(arrayOf("p", "q", "r").random(seeded))
	println(sequenceOf(1, 2, 3, 4).shuffled(seeded).toList())

	val dice = Dice(2024)
	println(List(20) { dice.roll() })
	println(Random(1).nextInt() == Random(1).nextInt())
	println(Random(1).nextInt() == Random(2).nextInt())
	println(runCatching { Random(1).nextInt(5, 5) }.exceptionOrNull()?.let { it::class.simpleName })
	println(runCatching { Random(1).nextInt(0) }.exceptionOrNull()?.message)

	// The default generator: only what must hold.
	val values = List(1000) { Random.nextInt(10) }
	println(values.all { it in 0..9 })
	println(List(100) { Random.nextDouble() }.all { it >= 0.0 && it < 1.0 })
	println(Random.nextInt(3, 4))
	println(Random.Default.nextLong(9L, 10L))
	println((1..5).shuffled().sorted())
}
