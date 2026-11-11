// Thread.sleep and the clock in a program that starts no thread.

import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

fun main() {
	val vBefore = System.currentTimeMillis()
	Thread.sleep(30)
	Thread.sleep(0)
	val vElapsed = System.currentTimeMillis() - vBefore
	println(vElapsed >= 25)
	println(measureTimeMillis { Thread.sleep(10) } >= 8)
	println(measureNanoTime { Thread.sleep(10) } >= 8_000_000L)
	try {
		Thread.sleep(-1)
	} catch (vError: IllegalArgumentException) {
		println(vError.message)
	}
}
