// Edges of the language the Kotlin compiler's box tests found (tests/box/): a jump inside a string template or a loop
// condition, `String()`, `x + "..."` on a type parameter bounded by `String?`, `is Unit`, and a `when` whose arm in
// the middle always applies. Each one stopped the translation or gave C that did not compile.

package ktc2test.regressions.languageedgesfromboxtests

import kotlin.test.Test

data class Holder(val x: Unit)

fun <T : String?> withSuffix(value: T) = value + "!"

fun <T : String> withSuffixNullable(value: T?) = value + "?"

fun jumpInTemplate(): String {
	var vResult = ""
	for (vIndex in 0 until 5) {
		vResult = vResult + (if (vIndex == 3) break else "$vIndex")
		vResult = "$vResult," + (if (vIndex == 1) continue else "")
	}
	return vResult
}

fun breakInDoWhileCondition(): Int {
	var vCount = 0
	do {
		vCount++
	} while (break)
	return vCount
}

fun breakInWhileCondition(): Int {
	var vRounds = 0
	while (true) {
		vRounds++
		while (break) {
			vRounds += 100
		}
	}
	return vRounds
}

fun middleTrue(value: Int): String = when {
	value < 0 -> "negative"
	true -> "other"
	value == 0 -> "zero"
	else -> "never"
}

@Test
fun main() {
	println(jumpInTemplate())
	println(breakInDoWhileCondition())
	println(breakInWhileCondition())
	println(String() + "empty" + String())
	println(withSuffix("a") + withSuffix(null))
	println(withSuffixNullable("b") + withSuffixNullable<String>(null))
	println(Holder(Unit).component1() is Unit)
	println(middleTrue(-1) + " " + middleTrue(0))
}
