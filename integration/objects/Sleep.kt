// Sleeping and timing a block, in a program that starts no thread.

import ktc2.concurrent.sleep
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

fun main() {
	val vElapsed = measureTimeMillis {
		sleep(30)
		sleep(0)
	}
	println(vElapsed >= 25)
	println(measureTimeMillis { sleep(10) } >= 8)
	println(measureNanoTime { sleep(10) } >= 8_000_000L)
	try {
		sleep(-1)
	} catch (vError: IllegalArgumentException) {
		println(vError.message)
	}
}
