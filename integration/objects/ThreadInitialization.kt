// An object used for the first time by several threads at once is initialized once, and no
// thread sees it before its initializer has finished.

import ktc2.concurrent.Thread
import ktc2.concurrent.sleep
import ktc2.concurrent.thread

var gCreated = 0

object Registry {
	val names = ArrayList<String>()
	var total = 0

	init {
		gCreated++
		// Long enough for the other threads to arrive while this runs.
		for (vIndex in 0 until 20000) {
			total += vIndex % 7
			if (vIndex % 4000 == 0) names.add("entry" + names.size)
		}
		sleep(20)
		names.add("last")
	}
}

enum class Level(val weight: Int) {
	LOW(1), MIDDLE(5), HIGH(10);

	companion object {
		val heaviest = entries.maxOf { it.weight }
	}
}

fun main() {
	val vSeen = IntArray(8)
	val vTotals = IntArray(8)
	val vWorkers = ArrayList<Thread>()
	for (vWorker in 0 until 8) {
		vWorkers.add(thread {
			vSeen[vWorker] = Registry.names.size
			vTotals[vWorker] = Registry.total + Level.heaviest
		})
	}
	for (vThread in vWorkers) vThread.join()
	println(gCreated)
	println(vSeen.joinToString())
	println(vTotals.joinToString())
	println(Registry.names)
	println(Level.HIGH.weight + Level.heaviest)
}
