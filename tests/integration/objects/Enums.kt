package objects

enum class Level { LOW, MEDIUM, HIGH }

enum class Planet(val mass: Double, val radius: Double) {
	MERCURY(3.303e+23, 2.4397e6),
	EARTH(5.976e+24, 6.37814e6);

	val gravity: Double get() = 6.67300E-11 * mass / (radius * radius)
	fun heavierThan(other: Planet): Boolean = mass > other.mass
}

enum class Op(val symbol: String) {
	ADD("+") {
		override fun apply(a: Int, b: Int): Int = a + b
	},
	SUB("-") {
		override fun apply(a: Int, b: Int): Int = a - b
		override fun toString(): String = "minus"
	},
	MUL("*") {
		override fun apply(a: Int, b: Int): Int = a * b
	};

	abstract fun apply(a: Int, b: Int): Int
	fun show(a: Int, b: Int): String = "$a $symbol $b = ${apply(a, b)}"
}

interface Named {
	fun label(): String
}

enum class Suit : Named {
	HEARTS, SPADES;

	override fun label(): String = name.lowercase2()
}

fun String.lowercase2(): String {
	var vOut = ""
	for (vI in 0 until length) {
		val vChar = this[vI]
		vOut += if (vChar in 'A'..'Z') (vChar + 32) else vChar
	}
	return vOut
}

fun describe(level: Level): String = when (level) {
	Level.LOW -> "quiet"
	Level.MEDIUM -> "normal"
	Level.HIGH -> "loud"
}

fun next(level: Level): Level = when (level) {
	Level.LOW -> Level.MEDIUM
	else -> Level.HIGH
}

fun main() {
	val vLevel = Level.MEDIUM
	println(vLevel)
	println(vLevel.name + " " + vLevel.ordinal)
	println(describe(vLevel) + " " + describe(next(vLevel)))
	println(vLevel == Level.MEDIUM)
	println(vLevel != next(vLevel))
	println(vLevel < Level.HIGH)
	println(Level.HIGH.compareTo(Level.LOW))
	println("level=$vLevel next=${next(vLevel)}")

	for (vEach in Level.values()) print("${vEach.ordinal}:$vEach ")
	println()
	println(Level.values().size)
	println(Level.valueOf("HIGH").ordinal)

	println(Planet.EARTH.heavierThan(Planet.MERCURY))
	println(Planet.EARTH.gravity)
	println(Planet.valueOf("MERCURY").radius)

	for (vOp in Op.values()) println(vOp.show(6, 3))
	println(Op.SUB)
	println(Op.ADD)
	println(Op.MUL.symbol + Op.MUL.name)
	val vPicked: Op = if (vLevel == Level.LOW) Op.ADD else Op.MUL
	println(vPicked.apply(7, 6))

	val vNamed: Named = Suit.SPADES
	println(vNamed.label())
	println(Suit.HEARTS.label())
	val vAny: Any = Suit.HEARTS
	println(vAny is Suit)
	println(vAny is Named)
	println(vAny == Suit.HEARTS)
	println(vAny.toString())
	val vMaybe: Level? = if (vLevel.ordinal > 5) Level.LOW else null
	println(vMaybe)
	println(vMaybe?.name ?: "none")
}
