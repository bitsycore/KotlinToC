package ktc2test.threads.paircyclesthreads

import kotlin.test.Test
import ktc2.concurrent.synchronized
import ktc2.concurrent.thread

// Two objects that hold each other are freed when nothing else holds either, also in a program with threads:
// pairs made on one thread are handed to others through a shared box, reached there through the pair's own
// fields (the inner object's reference to its outer one, the outer one's to its inner object), and let go of
// on either side while the other is still taking references. The leak check at the end has nothing to report,
// and what each thread adds up shows that none was freed while still in use.

class Ring(val id: Int) {
	val gate = Gate()

	inner class Gate {
		val ring: Ring get() = this@Ring
		fun weight(): Int = id % 7 + 1
	}
}

/** Where pairs are handed from one thread to another: one at a time, taken by whoever comes. */
class Box {
	private var mGates = ArrayList<Ring.Gate>()

	fun put(inGate: Ring.Gate) {
		synchronized(this) { mGates.add(inGate) }
	}

	fun take(): Ring.Gate? = synchronized(this) { if (mGates.isEmpty()) null else mGates.removeAt(mGates.size - 1) }
}

/** What the threads add up, read after they ended. */
class Totals {
	private var mMade = 0L
	private var mTaken = 0L

	fun made(inSum: Long) {
		synchronized(this) { mMade += inSum }
	}

	fun taken(inSum: Long) {
		synchronized(this) { mTaken += inSum }
	}

	fun text(): String = synchronized(this) { "made $mMade, taken $mTaken" }
}

@Test
fun main() {
	val vBox = Box()
	val vTotals = Totals()
	val vRounds = 20000
	val vMakers = (0 until 2).map { vMaker ->
		thread {
			var vSum = 0L
			for (vI in 0 until vRounds) {
				val vRing = Ring(vMaker * vRounds + vI)
				// Handed over through the inner object: the other thread reaches the ring through it.
				vBox.put(vRing.gate)
				vSum += vRing.gate.weight()
			}
			vTotals.made(vSum)
		}
	}
	val vTakers = (0 until 2).map {
		thread {
			var vSeen = 0
			var vSum = 0L
			while (vSeen < vRounds) {
				val vGate = vBox.take() ?: continue
				// Through the inner object to the outer one, and back: references counted while the maker lets go.
				val vRing = vGate.ring
				vSum += vRing.gate.weight() + vRing.id % 3
				vSeen++
			}
			vTotals.taken(vSum)
		}
	}
	vMakers.forEach { it.join() }
	vTakers.forEach { it.join() }
	println(vTotals.text())
	// Every ring was handed over once and taken once.
	var vMade = 0L
	var vTaken = 0L
	for (vId in 0 until 2 * vRounds) {
		vMade += vId % 7 + 1
		vTaken += vId % 7 + 1 + vId % 3
	}
	println("expected: made $vMade, taken $vTaken")
}
