// Every part of a string template, or of a chain of +, is evaluated before the first toString
// runs; and Any() makes an object.

import ktc2.concurrent.synchronized

class Counter {
	var count = 0
}

class Bumping(val counter: Counter, val name: String) {
	override fun toString(): String {
		counter.count += 10
		return "$name(${counter.count})"
	}
}

fun next(inCounter: Counter): Int {
	inCounter.count++
	return inCounter.count
}

fun main() {
	val vCounter = Counter()
	val vFirst = Bumping(vCounter, "first")
	val vSecond = Bumping(vCounter, "second")

	println("$vFirst sees ${vCounter.count}")
	println("${vCounter.count} then $vFirst then ${vCounter.count} then $vSecond then ${vCounter.count}")
	println("" + vFirst + vCounter.count + vSecond + vCounter.count)
	println("a" + next(vCounter) + vFirst + next(vCounter) + "z")
	val vText = "kept " + vFirst + " and " + vSecond
	println(vText + " / " + vCounter.count)

	val vBuilder = StringBuilder("x")
	println("$vBuilder ${vBuilder.append("y").length} $vBuilder")

	val vNullable: Bumping? = if (vCounter.count > 0) vFirst else null
	println("maybe $vNullable, count ${vCounter.count}")

	val vLock = Any()
	val vOther = Any()
	println(vLock == vLock)
	println(vLock == vOther)
	println(vLock.hashCode() == vLock.hashCode())
	val vTotal = synchronized(vLock) { vCounter.count + 1 }
	println(vTotal)
	val vThings = listOf(vLock, vOther, vLock)
	println(vThings.indexOf(vOther).toString() + " " + vThings.lastIndexOf(vLock))
}
