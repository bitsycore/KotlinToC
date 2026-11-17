package basic

data class Vector(val x: Double, val y: Double) {
	companion {
		val Zero: Vector get() = Vector(0.0, 0.0)
		const val DIMENSIONS = 2
		var created = 0
		fun unit(inAngle: Int): Vector = if (inAngle == 0) Vector(1.0, 0.0) else Vector(0.0, 1.0)
	}
}

companion val Vector.UnitX get() = Vector(1.0, 0.0)

companion fun Vector.diagonal(inSize: Double): Vector = Vector(inSize, inSize)

value class Complex(val real: Double, val imaginary: Double) {
	operator fun plus(inOther: Complex): Complex = Complex(real + inOther.real, imaginary + inOther.imaginary)
}

sealed interface Response

value class Found(val id: Int) : Response

value object Unknown : Response

fun describe(inResponse: Response): String = when (inResponse) {
	is Found -> "found ${inResponse.id}"
	Unknown -> "unknown"
}

class Logger(val prefix: String) {
	fun log(inText: String) = println("$prefix$inText")
}

context(logger: Logger)
fun greet(inName: String) {
	logger.log("hello $inName")
}

fun main() {
	println(Vector.Zero)
	println(Vector.UnitX)
	println(Vector.DIMENSIONS)
	Vector.created += 2
	println(Vector.created)
	println(Vector.unit(1))
	println(Vector.diagonal(3.0))

	val vNumbers: List<Int> = [1, 2, 3]
	println(vNumbers)
	val vSet: Set<String> = ["a", "b", "a"]
	println(vSet.size)
	val vArray: IntArray = [4, 5, 6]
	println(vArray.sum())
	val vMutable: MutableList<String> = ["x"]
	vMutable.add("y")
	println(vMutable)
	val vEmpty: List<Double> = []
	println(vEmpty.isEmpty())

	val vSum = Complex(1.0, 2.0) + Complex(0.5, -2.0)
	println(vSum)
	println(vSum == Complex(1.5, 0.0))
	println(describe(Found(7)))
	println(describe(Unknown))

	(val y, val x) = Vector(3.0, 4.0)
	println("$x $y")
	with(Logger("> ")) { greet("ktc2") }
	val vWhen = when (val vValue = vNumbers.size) {
		3 if vNumbers[0] == 1 -> "three starting with one"
		else -> "other $vValue"
	}
	println(vWhen)
}
