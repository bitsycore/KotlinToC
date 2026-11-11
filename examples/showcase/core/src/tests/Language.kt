package showcase.core.tests

import showcase.core.Group

// ==================
// MARK: Declarations the tests use
// ==================

private interface Named {
	val label: String

	fun greet(): String = "I am $label"
}

private open class Animal(override val label: String, val legs: Int) : Named {
	open fun sound(): String = "..."

	override fun toString(): String = "$label($legs)"
}

private class Dog(name: String) : Animal(name, 4) {
	override fun sound(): String = "woof"

	override fun greet(): String = super.greet() + ", a dog"
}

private class Bird(name: String, val canFly: Boolean) : Animal(name, 2) {
	override fun sound(): String = if (canFly) "tweet" else "squawk"
}

private data class Version(val major: Int, val minor: Int) : Comparable<Version> {
	override fun compareTo(other: Version): Int = if (major != other.major) major.compareTo(other.major) else minor.compareTo(other.minor)

	operator fun plus(inMinor: Int): Version = Version(major, minor + inMinor)

	operator fun inc(): Version = Version(major + 1, 0)

	companion object {
		val first = Version(1, 0)

		fun parse(inText: String): Version {
			val vParts = inText.split(".")
			return Version(vParts[0].toInt(), vParts[1].toInt())
		}
	}
}

private enum class Planet(val moons: Int) {
	MERCURY(0), EARTH(1), MARS(2), JUPITER(95);

	val isLonely: Boolean get() = moons == 0

	fun next(): Planet = entries[(ordinal + 1) % entries.size]
}

private sealed interface Expression

private data class Number(val value: Int) : Expression

private data class Sum(val left: Expression, val right: Expression) : Expression

private data class Product(val left: Expression, val right: Expression) : Expression

private object Unknown : Expression

private fun evaluate(inExpression: Expression): Int? = when (inExpression) {
	is Number -> inExpression.value
	is Sum -> evaluate(inExpression.left)?.let { vLeft -> evaluate(inExpression.right)?.let { vLeft + it } }
	is Product -> evaluate(inExpression.left)?.let { vLeft -> evaluate(inExpression.right)?.let { vLeft * it } }
	Unknown -> null
}

private class Stack<T> {
	private val mItems = ArrayList<T>()

	val size: Int get() = mItems.size

	fun push(inItem: T): Stack<T> {
		mItems.add(inItem)
		return this
	}

	fun pop(): T = mItems.removeAt(mItems.size - 1)

	fun <R> mapTo(inTransform: (T) -> R): Stack<R> {
		val vResult = Stack<R>()
		for (vItem in mItems) vResult.push(inTransform(vItem))
		return vResult
	}

	inner class Cursor {
		private var mAt = size

		fun hasNext(): Boolean = mAt > 0

		fun next(): T = mItems[--mAt]
	}
}

private class Counter {
	var count = 0
		private set

	val history: ArrayList<Int> by lazy { ArrayList<Int>() }

	lateinit var note: String

	fun step(inBy: Int = 1): Int {
		count += inBy
		history.add(count)
		return count
	}
}

private fun <T : Comparable<T>> largest(vararg inItems: T): T {
	var vBest = inItems[0]
	for (vItem in inItems) if (vItem > vBest) vBest = vItem
	return vBest
}

private tailrec fun gcd(inA: Int, inB: Int): Int = if (inB == 0) inA else gcd(inB, inA % inB)

private infix fun Int.times(inText: String): String = inText.repeat(this)

private val Int.isEven: Boolean get() = this % 2 == 0

private inline fun <T> measureCalls(inBlock: (() -> Unit) -> T): Pair<T, Int> {
	var vCalls = 0
	val vResult = inBlock { vCalls++ }
	return vResult to vCalls
}

// ==================
// MARK: The group
// ==================

fun languageGroup(): Group = Group("Language") { r ->
	// Classes, inheritance, interfaces.
	val vAnimals: List<Animal> = listOf(Dog("Rex"), Bird("Tweety", true), Bird("Pingu", false), Animal("Blob", 0))
	r.expect("virtual calls", "woof tweet squawk ...", vAnimals.joinToString(" ") { it.sound() })
	r.expect("super and interface defaults", "I am Rex, a dog | I am Blob", vAnimals[0].greet() + " | " + vAnimals[3].greet())
	r.expect("type tests", 2, vAnimals.count { it is Bird })
	r.expect("smart casts", listOf(true, false), vAnimals.filterIsInstance<Bird>().map { it.canFly })
	r.expect("toString", "[Rex(4), Tweety(2), Pingu(2), Blob(0)]", vAnimals.toString())

	// Data classes, operators, companions.
	val vVersion = Version.parse("2.7")
	var vNext = vVersion + 3
	vNext++
	val (vMajor, vMinor) = vVersion
	r.expect("data class equality", Version(2, 7), vVersion)
	r.expect("copy and hash", true, vVersion.copy(minor = 8) != vVersion && vVersion.hashCode() == Version(2, 7).hashCode())
	r.expect("operators", "Version(major=3, minor=0)", vNext.toString())
	r.expect("destructuring", 27, vMajor * 10 + vMinor)
	r.expect("comparisons", Version(2, 10), largest(Version.first, Version(2, 10), vVersion))

	// Enums.
	r.expect("enum entries", "MERCURY EARTH MARS JUPITER", Planet.entries.joinToString(" "))
	r.expect("enum members", "EARTH true 98", Planet.MERCURY.next().name + " " + Planet.MERCURY.isLonely + " " + Planet.entries.sumOf { it.moons })
	r.expect("valueOf and when", "two", when (Planet.valueOf("MARS")) {
		Planet.MERCURY -> "none"
		Planet.EARTH -> "one"
		Planet.MARS -> "two"
		Planet.JUPITER -> "many"
	})

	// Sealed types and nullability.
	val vTree = Sum(Number(2), Product(Number(3), Number(4)))
	r.expect("sealed when", 14, evaluate(vTree))
	r.expect("null through the tree", null, evaluate(Sum(vTree, Unknown)))
	val vMaybe: String? = if (vAnimals.size > 3) null else "x"
	r.expect("elvis and safe calls", "none 0", (vMaybe ?: "none") + " " + (vMaybe?.length ?: 0))

	// Generics, inner classes, lambdas.
	val vStack = Stack<Int>().push(1).push(2).push(3)
	val vTexts = vStack.mapTo { "n$it" }
	val vCursor = vTexts.Cursor()
	val vSeen = StringBuilder()
	while (vCursor.hasNext()) vSeen.append(vCursor.next())
	r.expect("generic class and inner class", "n3n2n1 3", vSeen.toString() + " " + vStack.pop())
	var vTotal = 0
	val vAdd = { vValue: Int -> vTotal += vValue }
	listOf(1, 2, 3, 4).forEach(vAdd)
	val vAdders = (1..3).map { vBase -> { vValue: Int -> vValue + vBase } }
	r.expect("closures", "10 [11, 12, 13]", "$vTotal ${vAdders.map { it(10) }}")
	val (vResult, vCalls) = measureCalls { vTick ->
		repeat(5) { vTick() }
		"done"
	}
	r.expect("inline functions", "done 5", "$vResult $vCalls")

	// Properties, defaults, extensions.
	val vCounter = Counter()
	vCounter.step()
	vCounter.step(inBy = 4)
	vCounter.note = "counted"
	r.expect("properties", "5 [1, 5] counted", "${vCounter.count} ${vCounter.history} ${vCounter.note}")
	r.expect("extensions", "ababab true false", (3 times "ab") + " " + 10.isEven + " " + 7.isEven)
	r.expect("tail recursion and varargs", "21 zebra", "${gcd(1071, 462)} ${largest("apple", "zebra", "mango")}")

	// Control flow.
	var vSteps = 0
	outer@ for (vRow in 1..5) {
		for (vColumn in 1..5) {
			if (vColumn > vRow) continue@outer
			if (vRow * vColumn > 12) break@outer
			vSteps++
		}
	}
	val vLabels = (0..6 step 2).map { if (it in 2..4) "mid" else if (it < 2) "low" else "high" }
	r.expect("loops and ranges", "9 [low, mid, mid, high]", "$vSteps $vLabels")
}
