package ktc2test.regressions.tailrecthroughwhen

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

// A `tailrec` function whose calls to itself are in the branches of a returned `if` or `when`, or at the end of a block,
// called itself for each round: 100,000 rounds overflowed the stack (`suspend` ones too).

tailrec fun countDown(counter: Int, d: Any): Int =
	if (counter == 0) {
		0
	} else if (counter == 5) {
		countDown(counter - 1, 999)
	} else when (d) {
		is String -> countDown(counter - 1, "is String")
		is Number -> countDown(counter, "is Number")
		else -> throw IllegalStateException()
	}

tailrec fun inBlock(counter: Int, total: Long): Long {
	return if (counter == 0) total else {
		val vNext = total + counter
		inBlock(counter - 1, vNext)
	}
}

tailrec suspend fun suspendCountDown(counter: Int, d: Any): Int =
	if (counter == 0) 0 else when (d) {
		is String -> suspendCountDown(counter - 1, "is String")
		else -> suspendCountDown(counter, "is Number")
	}

class TailrecThroughWhenTest {
	@Test
	fun throughWhen() {
		assertEquals(0, countDown(100_000, "test"))
	}

	@Test
	fun throughBlock() {
		assertEquals(5_000_050_000L, inBlock(100_000, 0L))
	}

	@Test
	fun suspendThroughWhen() {
		var vResult = -1
		suspend { vResult = suspendCountDown(100_000, "test") }.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
		assertEquals(0, vResult)
	}
}
