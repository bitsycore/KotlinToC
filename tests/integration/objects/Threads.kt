package objects

import ktc2.concurrent.Thread
import ktc2.concurrent.synchronized
import ktc2.concurrent.thread


/** Immutable data read by every worker: its count is touched from all threads at once. */
class Sample(val id: Int, val label: String)

class Counter {
	private var mValue = 0

	fun add(inAmount: Int) {
		synchronized(this) { mValue += inAmount }
	}

	fun value(): Int = synchronized(this) { mValue }
}

class Ledger {
	private val mEntries = ArrayList<String>()

	fun record(inEntry: String) {
		synchronized(this) { mEntries.add(inEntry) }
	}

	fun sorted(): List<String> = synchronized(this) { mEntries.sorted() }
}

class Worker(private val mId: Int, private val mLedger: Ledger) : Thread() {
	override fun run() {
		for (vStep in 0 until 3) mLedger.record("worker $mId step $vStep")
	}
}

fun sumOfIds(inSamples: List<Sample>, inRounds: Int): Long {
	var vSum = 0L
	// Each visit stores the sample, which every thread shares: one retain, and one release of the one before.
	val vHeld = arrayOfNulls<Sample>(1)
	for (vRound in 0 until inRounds) {
		for (vSample in inSamples) {
			vHeld[0] = vSample
			val vCopy = vHeld[0]!!
			vSum += vCopy.id + vCopy.label.length
		}
	}
	return vSum
}

fun main() {
	val vSamples = List(50) { Sample(it, "sample-$it") }
	val vResults = LongArray(4)
	val vCounter = Counter()

	val vThreads = List(4) { vIndex ->
		thread {
			vResults[vIndex] = sumOfIds(vSamples, 2000)
			for (vStep in 0 until 1000) vCounter.add(1)
		}
	}
	for (vThread in vThreads) vThread.join()
	for (vResult in vResults) println(vResult)
	println(vCounter.value())

	val vLedger = Ledger()
	val vWorkers = List(3) { Worker(it, vLedger) }
	vWorkers.forEach { it.start() }
	vWorkers.forEach { it.join() }
	vLedger.sorted().forEach { println(it) }

	// Not started until asked; joining twice is harmless.
	val vLazy = thread(start = false) { vCounter.add(100) }
	println(vCounter.value())
	vLazy.start()
	vLazy.join()
	vLazy.join()
	println(vCounter.value())

	// Shared strings built in one thread, read in another.
	val vBuilt = ArrayList<String>()
	thread { for (vIndex in 0 until 5) synchronized(vBuilt) { vBuilt.add("item $vIndex") } }.join()
	println(vBuilt)

	// A thread nobody joins still finishes before the program ends.
	thread { vCounter.add(1) }
	// An exception nobody catches ends its thread, not the program.
	val vFailing = thread { throw IllegalStateException("worker failed") }
	vFailing.join()
	println("still running")

	// Recursive locking, and nested locks on different objects.
	val vOuter = Counter()
	val vInner = Counter()
	synchronized(vOuter) {
		synchronized(vOuter) { vOuter.add(1) }
		synchronized(vInner) { vInner.add(2) }
	}
	println("${vOuter.value()} ${vInner.value()}")
	println("done")
}
