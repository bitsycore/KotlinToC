import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

/** What ordinary tests do with kotlinx-coroutines-test: runTest, virtual time, test dispatchers, flows. */

suspend fun slowSum(values: List<Int>): Int {
	var vSum = 0
	for (vValue in values) {
		delay(1_000)
		vSum += vValue
	}
	return vSum
}

fun main() {
	// Virtual time: an hour of delays takes no time, and the scheduler says how long it was.
	runTest {
		val vStart = currentTime
		println(slowSum(listOf(1, 2, 3)))
		println("virtual time: ${currentTime - vStart}")
	}

	// Coroutines launched in the test's scope run as the scheduler advances.
	runTest {
		val vEvents = ArrayList<String>()
		launch { delay(500); vEvents += "a at $currentTime" }
		launch { delay(200); vEvents += "b at $currentTime" }
		advanceTimeBy(300)
		vEvents += "advanced to $currentTime"
		advanceUntilIdle()
		println(vEvents)
	}

	// A StandardTestDispatcher waits for the scheduler; an UnconfinedTestDispatcher runs at once.
	runTest {
		val vOrder = ArrayList<String>()
		launch(StandardTestDispatcher(testScheduler)) { vOrder += "standard" }
		launch(UnconfinedTestDispatcher(testScheduler)) { vOrder += "unconfined" }
		vOrder += "after launch"
		runCurrent()
		println(vOrder)
	}

	// Flows and channels under virtual time.
	runTest {
		val vTicks = flow {
			repeat(3) {
				delay(100)
				emit(currentTime)
			}
		}.toList()
		println("ticks: $vTicks")
		val vChannel = Channel<Int>()
		launch { for (vIndex in 1..3) vChannel.send(vIndex * 10) ; vChannel.close() }
		println("received: ${vChannel.toList()}")
	}

	// A timeout in virtual time.
	runTest {
		val vResult = withTimeoutOrNull(1_000) { delay(5_000); "late" }
		println("timeout: $vResult at $currentTime")
	}
	println("done")
}
