package ktc2test.objects.enums

import kotlin.test.Test

// ==================
// MARK: Enums
// ==================

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

@Test
fun enums() {
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

// ==================
// MARK: EnumBasics
// ==================

enum class Color { RED, GREEN, BLUE }

fun testEnumValues() {
    val values = enumValues<Color>()
    println(values.size)
    if (values.size != 3) error("FAIL enumValues size")
    for (i in 0 until values.size) {
        println(values[i])
    }
}

fun testEnumValueOf() {
    val c = enumValueOf<Color>("GREEN")
    println(c)
    if (c != Color.GREEN) error("FAIL enumValueOf")
}

fun testColorDotValues() {
    val values = Color.values()
    println(values.size)
    if (values.size != 3) error("FAIL Color.values")
    for (i in 0 until values.size) {
        println(values[i])
    }
}

fun testColorDotValueOf() {
    val c = Color.valueOf("BLUE")
    println(c)
    if (c != Color.BLUE) error("FAIL Color.valueOf")
}

fun testEnumName() {
    val c = Color.RED
    println(c.name)
    println(c.ordinal)
    if (c.ordinal != 0) error("FAIL ordinal")
}

fun testEnumWhen() {
    val values = enumValues<Color>()
    var value = 0
    var redSeen = false; var greenSeen = false; var blueSeen = false
    repeat(values.size) {
        when (values[value]) {
            Color.RED   -> { println("Fire ! the color is RED");   redSeen   = true }
            Color.GREEN -> { println("Leafy ! the color is GREEN"); greenSeen = true }
            Color.BLUE  -> { println("Sad ! the color is BLUE");   blueSeen  = true }
        }
        value++
    }
    if (!redSeen || !greenSeen || !blueSeen) error("FAIL testEnumWhen: not all colors seen")
}

@Test
fun enumBasics() {
    testEnumValues()
    testEnumValueOf()
    testColorDotValues()
    testColorDotValueOf()
    testEnumName()
    testEnumWhen()
    println("ALL OK")
}

// ==================
// MARK: EnumEntries
// ==================

// Full Kotlin-style enum: primary ctor params, per-entry args, body method.
enum class EntryOp(val sym: String) {
	PLUS("+"), MINUS("-"), TIMES("*"), DIV("/");
	fun eval(a: Int, b: Int): Int = when (this) {
		PLUS  -> a + b
		MINUS -> a - b
		TIMES -> a * b
		DIV   -> a / b
	}
}

fun testEntryFields() {
	val p = EntryOp.PLUS
	println(p.sym)
	println(p.name)
	println(p.ordinal)
	if (p.sym     != "+")    error("FAIL EntryOp.PLUS.sym")
	if (p.name    != "PLUS") error("FAIL EntryOp.PLUS.name")
	if (p.ordinal != 0)      error("FAIL EntryOp.PLUS.ordinal")

	val t = EntryOp.TIMES
	if (t.sym     != "*")     error("FAIL EntryOp.TIMES.sym")
	if (t.name    != "TIMES") error("FAIL EntryOp.TIMES.name")
	if (t.ordinal != 2)       error("FAIL EntryOp.TIMES.ordinal")
}

fun testEquality() {
	val a = EntryOp.PLUS
	val b = EntryOp.PLUS
	val c = EntryOp.MINUS
	if (!(a == b)) error("FAIL: EntryOp.PLUS == EntryOp.PLUS")
	if ( (a == c)) error("FAIL: EntryOp.PLUS != EntryOp.MINUS")
	if (!(a != c)) error("FAIL: EntryOp.PLUS != EntryOp.MINUS (op !=)")
}

fun testWhen() {
	val all = EntryOp.values()
	var sum = 0
	for (i in 0 until all.size) {
		val op = all[i]
		val tag = when (op) {
			EntryOp.PLUS  -> 1
			EntryOp.MINUS -> 2
			EntryOp.TIMES -> 4
			EntryOp.DIV   -> 8
		}
		sum += tag
	}
	if (sum != 15) error("FAIL testWhen sum=$sum")
}

fun testMethod() {
	if (EntryOp.PLUS.eval(2, 3)  != 5)  error("FAIL EntryOp.PLUS.eval")
	if (EntryOp.MINUS.eval(7, 4) != 3)  error("FAIL EntryOp.MINUS.eval")
	if (EntryOp.TIMES.eval(6, 7) != 42) error("FAIL EntryOp.TIMES.eval")
	if (EntryOp.DIV.eval(20, 4)  != 5)  error("FAIL EntryOp.DIV.eval")
}

fun testValuesAndValueOf() {
	val all = EntryOp.values()
	if (all.size != 4) error("FAIL values size")
	if (all[0].name != "PLUS")  error("FAIL values[0]")
	if (all[3].name != "DIV")   error("FAIL values[3]")
	val pl = EntryOp.valueOf("PLUS")
	if (pl != EntryOp.PLUS) error("FAIL valueOf PLUS")
	val tm = EntryOp.valueOf("TIMES")
	if (tm.ordinal != 2) error("FAIL valueOf TIMES ordinal")
}

fun testPrintAndTemplate() {
	val op = EntryOp.MINUS
	// println(op) should print the entry name.
	println(op)
	// Inside a template: "$op" should also produce the entry name.
	val s = "op=$op"
	if (s != "op=MINUS") error("FAIL template, got $s")
}

@Test
fun enumEntries() {
	testEntryFields()
	testEquality()
	testWhen()
	testMethod()
	testValuesAndValueOf()
	testPrintAndTemplate()
	println("ALL OK")
}

// ==================
// MARK: EnumComparable
// ==================

// An enum entry is a Comparable (kotlin.Enum is one): seen as Any, cast to it, compared by selectors.

enum class Month { JANUARY, FEBRUARY, MARCH }

class Period(val year: Int, val month: Month)

@Test
fun enumComparable() {
	val vAny: Any = Month.MARCH
	println(vAny is Comparable<*>)
	println(compareValues(Month.JANUARY, Month.MARCH))
	@Suppress("UNCHECKED_CAST")
	println((vAny as Comparable<Any>).compareTo(Month.FEBRUARY))
	val vPeriods = listOf(Period(2024, Month.MARCH), Period(2023, Month.FEBRUARY), Period(2024, Month.JANUARY))
	println(vPeriods.sortedWith(compareBy({ it.year }, { it.month })).joinToString { "${it.year}-${it.month}" })
	println(compareValuesBy(vPeriods[0], vPeriods[2], Period::year, Period::month))
}
