// Values reached through nesting, which the Kotlin compiler's box tests found (tests/box/closures): a local function
// called from a local class or from a lambda in an `init` block, a lambda in a local class's superclass call that
// calls a local function, an outer object read from a method of an object returned by a method of another, and an
// inner class's default argument that uses the outer object. Each stopped the translation ("reference to ...").

package ktc2test.regressions.capturesthroughnesting

import kotlin.test.Test

interface Result {
	fun result(): String
}

open class Base(val make: () -> String)

class InitLocal {
	val text: String

	init {
		val vFlag = "init"
		fun flag(): String = vFlag
		text = { flag() }.let { it() }
	}
}

class Chain(val name: String) {
	fun first() = object : Result {
		fun second() = object : Result {
			override fun result() = name
		}

		override fun result() = second().result()
	}
}

class Outer(val name: String) {
	inner class Inner(val make: () -> String = { "inner of $name" })
}

fun localFunctionInLocalClass(): String {
	val vPrefix = "local"
	fun prefixed(inText: String) = "$vPrefix $inText"
	class Named {
		val name = prefixed("class")
	}
	return Named().name
}

fun localFunctionInSuperCall(): String {
	val vPrefix = "super"
	fun prefix() = vPrefix
	class Local(inSuffix: String) : Base({ prefix() + " " + inSuffix })
	return Local("call").make()
}

fun countedInLocalClass(): Int {
	var vCount = 0
	fun count() {
		vCount++
	}
	class Counter {
		fun run() {
			count()
			count()
		}
	}
	Counter().run()
	return vCount
}

@Test
fun main() {
	println(InitLocal().text)
	println(Chain("chain").first().result())
	println(Outer("outer").Inner().make())
	println(localFunctionInLocalClass())
	println(localFunctionInSuperCall())
	println(countedInLocalClass())
}
