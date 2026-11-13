// Threads that nobody joins: the program still waits for them before it ends, and what the
// system keeps for a join that never comes is given back with the thread object.

package unjoined

import ktc2.concurrent.Thread
import ktc2.concurrent.synchronized
import ktc2.concurrent.thread

class Counter {
	var value = 0
}

class Worker(private val mCounter: Counter, private val mAmount: Int) : Thread() {
	override fun run() {
		synchronized(mCounter) { mCounter.value += mAmount }
	}
}

fun startSome(counter: Counter) {
	// None of these is kept or joined.
	for (vI in 1..8) thread { synchronized(counter) { counter.value += vI } }
	Worker(counter, 100).start()
	// One that is never started at all.
	Worker(counter, 1000)
	// One that is joined, next to them.
	val vJoined = Worker(counter, 10000)
	vJoined.start()
	vJoined.join()
	vJoined.join()
}

fun main() {
	val vCounter = Counter()
	startSome(vCounter)
	// The joined one has run for certain; the others are waited for when the program ends.
	println(synchronized(vCounter) { vCounter.value } >= 10000)
	val vLate = thread(start = false) { synchronized(vCounter) { vCounter.value += 1 } }
	vLate.start()
	vLate.join()
	println("done")
}
