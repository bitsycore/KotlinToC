// An inline function whose lambda holds a labeled return of another inline call (`return@forEach`): the inline
// function's own `return` gives its value to its caller, which goes on (Kotlin/Native's test runner, measureTime).

package ktc2test.regressions.inlinereturninlambda

import kotlin.test.Test

inline fun <T> measured(block: () -> Unit, result: () -> T): T {
	block()
	return result()
}

inline fun twice(block: () -> Unit): Int {
	block()
	block()
	return 2
}

fun sumSkippingZeros(inValues: List<Int>): Int {
	var vSum = 0
	val vMeasured = measured({
		inValues.forEach {
			if (it == 0) return@forEach
			vSum += it
		}
	}) { "done" }
	println("measured: $vMeasured, sum $vSum")
	val vCount = twice {
		for (vValue in inValues) {
			if (vValue == 0) continue
			vSum += vValue
		}
		inValues.forEach { if (it < 0) return@forEach }
	}
	println("twice: $vCount, sum $vSum")
	return vSum
}

fun labeledInside(inValues: List<Int>): String {
	val vText = StringBuilder()
	val vLength = run {
		inValues.forEach { vValue ->
			if (vValue % 2 == 0) return@forEach
			vText.append(vValue)
		}
		vText.length
	}
	return "$vText ($vLength)"
}

/** The same inline function inside itself: each `return` of it gives the value of its own call. */
fun nestedSame(inGroups: List<List<Int>>): String {
	val vLines = ArrayList<String>()
	val vOuter = measured({
		inGroups.forEach { vGroup ->
			if (vGroup.isEmpty()) return@forEach
			val vInner = measured({ vLines += "group of ${vGroup.size}" }) { vGroup.sum() }
			vLines += "sum $vInner"
		}
	}) { vLines.size }
	vLines += "lines before: $vOuter"
	return vLines.joinToString(", ")
}

@Test
fun main() {
	println(nestedSame(listOf(listOf(1, 2), emptyList(), listOf(3))))
	println(sumSkippingZeros(listOf(1, 0, 2, 0, 3)))
	println(labeledInside(listOf(1, 2, 3, 4, 5)))
	println("end")
}
