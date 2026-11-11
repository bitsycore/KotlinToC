package objects

class Parser {
	fun parse(text: String): Int = text.toInt()
}

@JvmInline
value class Meters(val value: Double) {
	operator fun plus(other: Meters): Meters = Meters(value + other.value)
	fun describe(): String = "$value m"
}

fun risky(n: Int): Result<Int> = if (n < 0) Result.failure(IllegalArgumentException("negative: $n")) else Result.success(n * 2)

fun main() {
	val vOk = runCatching { Parser().parse("42") }
	val vBad = runCatching { Parser().parse("4x2") }
	println(vOk.isSuccess)
	println(vOk.getOrNull())
	println(vBad.isFailure)
	println(vBad.exceptionOrNull() is NumberFormatException)
	println(vBad.getOrDefault(-1))
	println(vBad.getOrElse { -2 })
	println(vOk.map { it + 1 }.getOrThrow())
	println(vBad.map { it + 1 }.isFailure)
	println(vOk.fold({ "ok $it" }, { "failed" }))
	println(vBad.fold({ "ok $it" }, { "failed" }))
	vOk.onSuccess { println("success $it") }.onFailure { println("never") }
	vBad.onSuccess { println("never") }.onFailure { println("failure") }
	println(vBad.recover { 0 }.getOrThrow())
	println(risky(4).getOrThrow())
	try {
		risky(-1).getOrThrow()
	} catch (vError: IllegalArgumentException) {
		println(vError.message)
	}
	println(vOk)
	println(listOf(risky(1), risky(-1)).map { it.isSuccess })
	val vLength = Meters(1.5) + Meters(2.0)
	println(vLength.describe())
	println(vLength == Meters(3.5))
	println(listOf(Meters(1.0), Meters(2.0)).map { it.value })
}
