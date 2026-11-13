// Numbers, control flow, exceptions and the scope functions, as ordinary code uses them.
import kotlin.math.*

class Account(var balance: Long) : AutoCloseable {
	var closed = false
	fun withdraw(inAmount: Long): Long {
		require(inAmount > 0) { "amount must be positive: $inAmount" }
		check(!closed) { "account is closed" }
		if (inAmount > balance) throw InsufficientFunds(inAmount - balance)
		balance -= inAmount
		return balance
	}
	override fun close() { closed = true; println("closed") }
}

class InsufficientFunds(val missing: Long) : Exception("missing $missing")
open class AppError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class DeepError(message: String) : AppError(message)

fun classify(inValue: Any?): String = when (inValue) {
	null -> "null"
	is Int if inValue < 0 -> "negative"
	0, 1 -> "small"
	in 2..9 -> "digit"
	is Int -> "int"
	is Long, is Short -> "whole"
	is Double -> if (inValue.isNaN()) "nan" else "real"
	is String -> when { inValue.isEmpty() -> "empty"; inValue.length > 3 -> "long text"; else -> "text" }
	is List<*> -> "list of ${inValue.size}"
	is Throwable -> "error ${inValue.message}"
	else -> "other"
}

fun risky(inStep: Int): String {
	try {
		when (inStep) {
			1 -> throw IllegalStateException("one")
			2 -> throw DeepError("two")
			3 -> return "three returned"
			4 -> error("four")
			5 -> listOf(1)[9]
			6 -> (null as String?)!!.length
			7 -> "x".toInt()
			8 -> 1 / (inStep - 8)
			9 -> throw AppError("outer", IllegalArgumentException("inner"))
		}
		return "no failure"
	} catch (inError: AppError) {
		return "app: ${inError.message} / ${inError.cause?.message} / ${inError is DeepError}"
	} catch (inError: IllegalStateException) {
		return "state: ${inError.message}"
	} catch (inError: IndexOutOfBoundsException) {
		return "index"
	} catch (inError: NullPointerException) {
		return "null"
	} catch (inError: NumberFormatException) {
		return "format"
	} catch (inError: ArithmeticException) {
		return "arithmetic: ${inError.message}"
	} finally {
		print("[finally $inStep] ")
	}
}

tailrec fun gcd(inA: Long, inB: Long): Long = if (inB == 0L) inA else gcd(inB, inA % inB)
fun fib(inN: Int): Long = if (inN < 2) inN.toLong() else fib(inN - 1) + fib(inN - 2)
infix fun Int.pow(inBy: Int): Long { var vResult = 1L; repeat(inBy) { vResult *= this }; return vResult }
operator fun Int.times(inText: String): String = inText.repeat(this)
typealias Check = (Int) -> Boolean

fun main() {
	println("${7 / 2} ${-7 / 2} ${7 % 3} ${-7 % 3} ${7.0 / 2} ${7 / 2.0} ${1 shl 10} ${-16 shr 2} ${-16 ushr 28} ${0xFF and 0x0F} ${5 or 2} ${5 xor 1} ${5.inv()} ${Int.MAX_VALUE + 1} ${Long.MAX_VALUE + 1} ${Int.MIN_VALUE - 1} ${(-5).absoluteValue} ${abs(-2.5)} ${3.coerceIn(5, 9)} ${12.coerceAtMost(10)} ${1.coerceAtLeast(4)} ${5.compareTo(7)} ${maxOf(1, 9, 4)} ${minOf(2.5, 1.5)}")
	println("${1.5.toInt()} ${-1.5.toInt()} ${2.5.roundToInt()} ${3.5.roundToInt()} ${-2.5.roundToInt()} ${2.4.roundToLong()} ${floor(-1.5)} ${ceil(1.2)} ${round(2.5)} ${truncate(-1.7)} ${1e10.toInt()} ${Double.NaN.toInt()} ${(-1e30).toLong()} ${300.toByte()} ${70000.toShort()} ${3000000000L.toInt()} ${65.toChar()} ${'A'.code} ${1.toDouble()} ${1.9f.toInt()} ${Float.MAX_VALUE.toDouble() > 1e38} ${0.1 + 0.2} ${0.1f + 0.2f} ${1.0 / 3} ${1f / 3} ${100.0} ${1e21} ${1e-5} ${123456789.123} ${1.0E7} ${0.001} ${12345.678f}")
	println("${sqrt(16.0)} ${2.0.pow(10)} ${2 pow 10} ${hypot(3.0, 4.0)} ${PI.toInt()} ${E > 2.7} ${sign(-3.0)} ${10.0.rem(3)} ${5.0.mod(-3.0)} ${Double.MAX_VALUE > 1e308} ${Double.MIN_VALUE > 0} ${(0.0 / 0.0).isNaN()} ${1.0 / 0.0} ${-1.0 / 0.0} ${Double.NaN == Double.NaN} ${0.0 == -0.0} ${Double.POSITIVE_INFINITY.isInfinite()} ${1.5.isFinite()} ${Int.MAX_VALUE} ${Long.MIN_VALUE} ${Byte.MAX_VALUE} ${Short.MIN_VALUE} ${UInt.MAX_VALUE} ${Char.MAX_VALUE.code} ${Int.SIZE_BITS} ${Long.SIZE_BYTES} ${5.countOneBits()} ${8.countTrailingZeroBits()} ${1.rotateLeft(31)} ${0x0F.takeHighestOneBit()}")
	println("${gcd(1071, 462)} ${fib(20)} ${3 * "ab"} ${(1..5).fold(1) { vAll, vOne -> vAll * vOne }} ${10.downTo(7).toList()} ${1L + 2} ${1 + 2L} ${1.5f + 1} ${2 * 2.5} ${'a' + 2} ${'c' - 'a'} ${5.toLong() * 1000000000} ${1_000_000} ${0b1010} ${0x1F} ${1e3} ${5u + 3u} ${5u - 6u} ${(-1).toUInt()} ${250.toUByte()} ${10uL * 10uL} ${7u / 2u} ${7u % 2u} ${3u.toInt() - 5}")

	println(listOf<Any?>(null, -3, 0, 5, 42, 7L, 2.5, Double.NaN, "", "ab", "abcd", listOf(1, 2), IllegalStateException("x"), 'c').map { classify(it) })
	for (vStep in 0..9) println(risky(vStep))
	val vAccount = Account(100)
	println("${vAccount.withdraw(30)} ${runCatching { vAccount.withdraw(500) }.exceptionOrNull()?.message} ${runCatching { vAccount.withdraw(-1) }.exceptionOrNull()?.message} ${runCatching { vAccount.withdraw(10) }.getOrDefault(-1)} ${runCatching { 5 }.map { it * 2 }.getOrNull()} ${runCatching { error("e") }.isFailure} ${runCatching { error("e") }.getOrElse { "recovered" }}")
	val vUsed = vAccount.use { it.withdraw(10) }
	println("$vUsed ${vAccount.closed}")
	try { vAccount.withdraw(1) } catch (inError: IllegalStateException) { println(inError.message) }
	try { Account(1).use { it.withdraw(5) } } catch (inError: InsufficientFunds) { println("${inError.message} ${inError.missing}") }
	try { requireNotNull(null as String?) { "needed" } } catch (inError: IllegalArgumentException) { println(inError.message) }
	try { checkNotNull(null as Int?) } catch (inError: IllegalStateException) { println(inError.message) }
	try { throw AppError("top", DeepError("root")) } catch (inError: Exception) { println("${inError.message} ${inError.cause?.message} ${inError.cause is AppError}") }
	try { try { error("inner") } finally { println("inner finally") } } catch (inError: IllegalStateException) { println("outer ${inError.message}") }
	val vNested = try { try { "x".toInt() } catch (inError: NumberFormatException) { throw IllegalStateException("wrapped", inError) } } catch (inError: IllegalStateException) { -1 }
	println("$vNested ${try { 1 } finally { print("f ") }} ${try { error("x") } catch (inError: Exception) { 2 }}")
	println(TODO_or("fallback"))
	results()
	println("${listOf(Unit, Unit).size} ${Unit} ${run { }} ${listOf(1).forEach { }} ${(Unit as Any) == Unit} ${mapOf("k" to Unit)}")

	val vEven: Check = { it % 2 == 0 }
	var vCount = 0
	outer@ for (vI in 1..5) {
		for (vJ in 1..5) {
			if (vJ == 3) continue@outer
			if (vI == 4) break@outer
			vCount += vI * vJ
		}
	}
	var vWhile = 0
	do { vWhile += 3 } while (vWhile < 10)
	while (true) { if (vEven(vWhile)) break; vWhile++ }
	println("$vCount $vWhile ${(1..10).filter(vEven)} ${listOf(1, 2, 3).forEachIndexedSum()}")
	listOf(1, 2, 3, 4).forEach { if (it == 3) return@forEach; print("$it ") }
	run loop@{ listOf(1, 2, 3, 4).forEach { if (it == 3) return@loop; print("$it ") } }
	println()

	val vPerson = StringBuilder("Ann").also { it.append("!") }.let { "<$it>" }
	val vApplied = ArrayList<Int>().apply { add(1); add(2) }.also { it += 3 }
	val vWith = with(vApplied) { sum() + size }
	val vRun = vApplied.run { first() + last() }
	val vTaken = vApplied.takeIf { it.size > 2 }?.size ?: 0
	val vUnless = vApplied.takeUnless { it.isEmpty() }?.first()
	val vNull: String? = null
	println("$vPerson $vApplied $vWith $vRun $vTaken $vUnless ${vNull?.length} ${vNull?.let { 1 } ?: 2} ${vNull ?: "default"} ${vNull.isNullOrEmpty()} ${vNull == null} ${(vNull ?: "").length} ${vNull?.uppercase()?.length ?: -1} ${listOf("a", null).mapNotNull { it?.uppercase() }} ${repeatText(3)} ${lazySum()}")
	val (vFirst, vSecond, vThird) = Triple(1, "two", 3.0)
	val (vKey, vValue) = mapOf("k" to 9).entries.first()
	val vLambda: (Int, Int) -> Int = { vA, vB -> vA + vB }
	val vReference = ::fib
	val vBound = "abc"::plus
	val vMember = String::length
	println("$vFirst $vSecond $vThird $vKey $vValue ${vLambda(2, 3)} ${vReference(10)} ${vBound("d")} ${vMember("four")} ${listOf("a", "bb").map(String::length)} ${listOf(1, 2).map(::fib)} ${compose({ it + 1 }, { it * 2 })(5)} ${(1..3).map { vIt -> { vIt * 10 } }.map { it() }}")
}

fun results() {
	val vA = runCatching { 5 }.map { it * 2 }.getOrNull()
	println(vA)
	val vB = runCatching { error("e") }.isFailure
	println(vB)
	val vC = runCatching { error("e") }.getOrElse { "recovered" }
	println(vC)
	val vD: Result<Int> = runCatching { "x".toInt() }
	println("${vD.isSuccess} ${vD.getOrDefault(-1)} ${vD.exceptionOrNull()?.message} ${vD.getOrElse { 7 }} ${vD.fold({ "ok" }, { "bad" })} ${vD.recover { 3 }.getOrThrow()}")
	val vE = Result.success("s")
	vE.onSuccess { println("got $it") }.onFailure { println("never") }
	println("${vE.getOrThrow()} $vE ${Result.failure<Int>(IllegalStateException("f")).exceptionOrNull()?.message}")
	try { vD.getOrThrow() } catch (inError: NumberFormatException) { println("thrown") }
}

fun TODO_or(inFallback: String): String = try { TODO("later") } catch (inError: NotImplementedError) { "${inError.message} $inFallback" }
fun List<Int>.forEachIndexedSum(): Int { var vSum = 0; forEachIndexed { vIndex, vValue -> vSum += vIndex * vValue }; return vSum }
fun repeatText(inTimes: Int): String = buildString { repeat(inTimes) { append(it) } }
fun lazySum(): Int { val vLazy by lazy { 40 }; var vDelegated by kotlin.properties.Delegates.observable(0) { _, inOld, inNew -> print("[$inOld->$inNew] ") }; vDelegated = 2; return vLazy + vDelegated }
fun compose(inFirst: (Int) -> Int, inSecond: (Int) -> Int): (Int) -> Int = { inSecond(inFirst(it)) }
