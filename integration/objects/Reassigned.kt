// A variable that is given a new object by a call while it still holds the one before: the
// new one is built in the frame when nothing else keeps the old ones, and each value must
// stay what it was for as long as something reads it.

package reassigned

data class Vec(val x: Int, val y: Int) {
	operator fun plus(other: Vec): Vec = Vec(x + other.x, y + other.y)

	fun scaled(factor: Int): Vec = Vec(x * factor, y * factor)

	fun checked(limit: Int): Vec = if (x > limit) throw IllegalStateException("past $limit: $x") else Vec(x + 1, y)
}

class Counter(var count: Int) {
	fun next(): Counter = Counter(count + 1)
}

/** The plain case: one variable, a new object on each turn. */
fun walk(steps: Int): Vec {
	var vPlace = Vec(0, 0)
	for (vI in 0 until steps) vPlace = vPlace + Vec(vI, 1)
	return Vec(vPlace.x, vPlace.y)
}

fun sumOfWalk(steps: Int): Int {
	var vPlace = Vec(1, 2)
	var vSum = 0
	for (vI in 0 until steps) {
		vPlace = vPlace + Vec(vI % 3, vI % 5)
		vSum += vPlace.x - vPlace.y
	}
	return vSum
}

/** Another local still reads the object before, across two assignments. */
fun withPrevious(steps: Int): Int {
	var vPlace = Vec(1, 1)
	var vSum = 0
	for (vI in 0 until steps) {
		val vBefore = vPlace
		vPlace = vPlace + Vec(1, 0)
		val vBetween = vPlace
		vPlace = vPlace.scaled(2)
		vPlace = vPlace + vBefore
		vSum += vBefore.x + vBetween.x * 10 + vPlace.x * 100 + vBefore.y + vBetween.y + vPlace.y
	}
	return vSum
}

/** Three variables that take turns. */
fun rotate(steps: Int): String {
	var vA = Vec(1, 0)
	var vB = Vec(0, 1)
	var vC = Vec(1, 1)
	for (vI in 0 until steps) {
		val vKept = vA
		vA = vB + vC
		vB = vC + vKept
		vC = vKept.scaled(2)
	}
	return "$vA $vB $vC"
}

/** Some of the objects get out: all of them are then ordinary objects. */
fun collected(steps: Int): List<Vec> {
	val vAll = ArrayList<Vec>()
	var vPlace = Vec(0, 0)
	for (vI in 0 until steps) {
		vPlace = vPlace + Vec(1, vI)
		if (vI % 2 == 0) vAll.add(vPlace)
	}
	return vAll
}

var gLast: Vec? = null

fun remembered(steps: Int): Int {
	var vPlace = Vec(0, 0)
	for (vI in 0 until steps) vPlace = vPlace + Vec(2, 3)
	gLast = vPlace
	return vPlace.x
}

/** The block of the variable is entered again and again, and so is the function. */
fun nested(depth: Int): Int {
	var vTotal = 0
	for (vRound in 0 until 3) {
		var vPlace = Vec(vRound, depth)
		for (vI in 0 until 4) {
			vPlace = vPlace + Vec(1, 1)
			if (depth > 0 && vI == 2) vTotal += nested(depth - 1)
		}
		vTotal += vPlace.x * vPlace.y
	}
	return vTotal
}

/** The call fails: the variable keeps what it had. */
fun failing(limit: Int): String {
	var vPlace = Vec(0, 7)
	try {
		for (vI in 0 until 10) vPlace = vPlace.checked(limit)
	} catch (vError: IllegalStateException) {
		return "${vError.message} at $vPlace"
	}
	return "no failure, $vPlace"
}

/** Null in between, and a condition. */
fun sometimes(steps: Int): Int {
	var vPlace: Vec? = null
	var vSum = 0
	for (vI in 0 until steps) {
		vPlace = if (vPlace == null) Vec(vI, vI) else vPlace + Vec(1, 2)
		vSum += vPlace.x + vPlace.y
		if (vI % 4 == 3) vPlace = null
	}
	return vSum
}

/** An object with a `var`: changing the new one must not show in the old one. */
fun counters(steps: Int): Int {
	var vCounter = Counter(0)
	var vSum = 0
	for (vI in 0 until steps) {
		val vOld = vCounter
		vCounter = vCounter.next()
		vCounter.count += 10
		vSum += vOld.count + vCounter.count
	}
	return vSum
}

fun main() {
	println(walk(1000))
	println(sumOfWalk(100000))
	println(withPrevious(10))
	println(rotate(7))
	println(collected(6))
	println("${remembered(5)} $gLast")
	gLast = null
	println(nested(3))
	println(failing(4))
	println(failing(100))
	println(sometimes(50))
	println(counters(100))
}
