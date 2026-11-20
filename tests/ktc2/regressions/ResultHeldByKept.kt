// A function returned a new object that it also stored, through another new object, somewhere that outlives the call:
// it was given a form that builds the object in the caller's frame, and what kept it then pointed into a frame that
// was gone. Found with Kotlin/Native's workers: `requestTermination()` puts its future in a job of the worker's queue,
// and the worker's thread counted that future of the main thread's frame at the same time as it.

package ktc2test.regressions.resultheldbykept

import kotlin.test.Test
import kotlin.test.assertEquals

class Holder(var value: Int)

class Job(val holder: Holder)

val gJobs = ArrayList<Job>()

fun register(inValue: Int): Holder {
	val vHolder = Holder(inValue)
	gJobs.add(Job(vHolder))
	return vHolder
}

fun useOnce(inValue: Int): Int {
	val vHolder = register(inValue)
	return vHolder.value
}

/** Uses its own frame, where the one of [useOnce] was. */
fun other(inSeed: Int): Int {
	val vValues = IntArray(16) { it * inSeed }
	return vValues.sum()
}

@Test
fun resultHeldByKept() {
	assertEquals(7, useOnce(7))
	assertEquals(8, useOnce(8))
	println(other(3))
	assertEquals(listOf(7, 8), gJobs.map { it.holder.value })
	gJobs.forEach { it.holder.value++ }
	assertEquals(listOf(8, 9), gJobs.map { it.holder.value })
	gJobs.clear()
}
