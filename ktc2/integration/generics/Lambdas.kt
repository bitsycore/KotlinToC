package generics

// ==================
// MARK: Inline functions
// ==================

inline fun <T> measure(label: String, block: () -> T): T {
	println("start $label")
	val vResult = block()
	println("end $label")
	return vResult
}

inline fun times(count: Int, action: (Int) -> Unit) {
	for (vI in 0 until count) action(vI)
}

inline fun <T, R> T.transform(block: (T) -> R): R = block(this)

inline fun <T> T.alsoDo(block: (T) -> Unit): T {
	block(this)
	return this
}

inline fun fold(values: IntArray, initial: Int, operation: (Int, Int) -> Int): Int {
	var vAccumulator = initial
	for (vValue in values) vAccumulator = operation(vAccumulator, vValue)
	return vAccumulator
}

inline fun firstMatch(values: IntArray, predicate: (Int) -> Boolean): Int {
	for (vValue in values) {
		if (predicate(vValue)) return vValue
	}
	return -1
}

inline fun twiceEach(count: Int, action: (Int) -> Unit) = times(count) {
	action(it)
	action(it)
}

fun findSquare(values: IntArray, target: Int): Int {
	times(values.size) { vIndex ->
		if (values[vIndex] * values[vIndex] == target) return vIndex
	}
	return -1
}

// ==================
// MARK: Function values
// ==================

fun apply(f: (Int) -> Int, x: Int): Int = f(x)

fun adder(n: Int): (Int) -> Int = { it + n }

fun compose(f: (Int) -> Int, g: (Int) -> Int): (Int) -> Int = { g(f(it)) }

fun counter(): () -> Int {
	var vCount = 0
	return {
		vCount++
		vCount
	}
}

fun greeter(greeting: String): (String) -> String = { vName -> "$greeting, $vName!" }

fun each(values: Array<String>, action: (String) -> Unit) {
	for (vValue in values) action(vValue)
}

fun pick(flag: Boolean): (Double) -> Double = if (flag) { vX -> vX * 2 } else { vX -> vX / 2 }

class Button(val label: String) {
	var onClick: (() -> Unit)? = null
	var clicks = 0

	fun click() {
		clicks++
		onClick?.invoke()
	}

	fun handler(): () -> String = { "$label clicked $clicks" }
}

fun twice(x: Int): Int = x * 2

class Scaler(val factor: Int) {
	fun scale(x: Int): Int = x * factor
}

fun main() {
	// Inline functions: lambdas are expanded, locals are shared with the caller.
	var vTotal = 0
	times(4) { vTotal += it }
	println(vTotal)
	println(measure("sum") { vTotal * 2 })
	println(measure("text") { "t$vTotal" })
	println(7.transform { it * 6 })
	println("abc".transform { it.length }.transform { it + 0.5 })
	println(Scaler(3).alsoDo { println("made ${it.factor}") }.scale(5))
	val vData = intArrayOf(3, 8, 5, 12)
	println(fold(vData, 0) { vA, vB -> vA + vB })
	println(fold(vData, 1) { vA, vB -> if (vB > vA) vB else vA })
	println(firstMatch(vData) { it > 4 })
	println(firstMatch(vData) { it > 40 })
	println(findSquare(vData, 25))
	println(findSquare(vData, 7))
	var vCalls = 0
	twiceEach(3) { vCalls += it + 1 }
	println(vCalls)
	times(2) { vOuter ->
		times(2) { vInner -> print("$vOuter$vInner ") }
	}
	println()

	// Function values
	println(apply({ it * 3 }, 4))
	val vAdd5 = adder(5)
	println(vAdd5(1) + vAdd5(10))
	println(compose(adder(1), { it * 10 })(4))
	val vNext = counter()
	val vOther = counter()
	println("${vNext()} ${vNext()} ${vNext()} ${vOther()}")
	println(greeter("hello")("ann"))
	var vJoined = ""
	each(arrayOf("a", "b", "c")) { vJoined += it }
	println(vJoined)
	println(pick(true)(3.0) + pick(false)(3.0))

	// Captured variables are shared, not copied.
	var vShared = 1
	val vBump = { vShared *= 2 }
	vBump()
	vBump()
	vShared += 1
	println(vShared)
	val vRead = { vShared }
	vShared = 100
	println(vRead())

	// Closures stored in objects, capturing objects
	val vButton = Button("ok")
	var vLog = ""
	vButton.onClick = { vLog += "[${vButton.label}:${vButton.clicks}]" }
	vButton.click()
	vButton.click()
	println(vLog)
	println(vButton.handler()())
	vButton.onClick = null
	vButton.click()
	println(vButton.clicks)

	// Function references and local functions
	println(apply(::twice, 21))
	val vScaler = Scaler(4)
	println(apply(vScaler::scale, 5))
	val vOperations = arrayOf<(Int) -> Int>(::twice, adder(100), { it - 1 })
	var vValue = 1
	for (vOperation in vOperations) vValue = vOperation(vValue)
	println(vValue)
	fun local(a: Int): Int = a + vTotal
	println(local(1))
	fun factorial(n: Int): Int = if (n <= 1) 1 else n * factorial(n - 1)
	println(factorial(6))
	var vDepth = 0
	fun descend(n: Int) {
		vDepth++
		if (n > 0) descend(n - 1)
	}
	descend(4)
	println(vDepth)
	val vLazy: () -> Unit = { println("ran") }
	vLazy()
	val vNullable: ((Int) -> Int)? = if (vDepth > 100) adder(1) else null
	println(vNullable?.invoke(1))
}
