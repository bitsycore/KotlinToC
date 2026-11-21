package ktc2test.regressions.unitlocalincoroutine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.coroutines.*
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

// A local of type Unit in a coroutine (`measureTimedValue { ... }` of a block that returns Unit keeps its result in
// `val result = block()`) was made a field of the coroutine's frame of type void, which C refuses.

/** Suspends once and resumes at once. */
suspend fun pause(): Unit = suspendCoroutine { it.resume(Unit) }

suspend fun keepsUnit(): String {
	val vResult: Unit = pause()
	pause()
	return "kept $vResult"
}

/** Runs [block] as a coroutine that completes at once, and gives its result. */
fun <T> complete(block: suspend () -> T): T {
	var vResult: Result<T>? = null
	block.startCoroutine(Continuation(EmptyCoroutineContext) { vResult = it })
	return vResult!!.getOrThrow()
}

class UnitLocalInCoroutineTest {
	@Test
	fun unitAcrossSuspension() {
		assertEquals("kept kotlin.Unit", complete { keepsUnit() })
	}

	@Test
	fun measuredSuspension() {
		val vTime = complete { TimeSource.Monotonic.measureTime { pause() } }
		assertEquals(true, vTime.isFinite())
	}

	@Test
	fun measuredUnitValue() {
		val vMeasured = complete { TimeSource.Monotonic.measureTimedValue { pause() } }
		assertEquals(Unit, vMeasured.value)
	}
}
