package ktc2test.regressions.weakcellandtargetendtogether

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import ktc2.WeakRef
import ktc2.concurrent.thread

// The last weak reference to an object given up on one thread while the object ends on another: the object's end
// looked for its weak cell in the table after the other thread had taken it out (a crash in `weak_link`). Found by
// the reaper of kotlinx.coroutines, which drops the weak cell of a job that is being destroyed elsewhere.

class Payload(val number: Int)

@OptIn(ExperimentalAtomicApi::class)
class WeakCellAndTargetEndTogetherTest {
	@Test
	fun endTogether() {
		var vEnded = 0
		repeat(3_000) { vRound ->
			val vTarget = AtomicReference<Payload?>(Payload(vRound))
			val vWeak = AtomicReference<WeakRef<Payload>?>(WeakRef(vTarget.load()!!))
			val vReady = AtomicInt(0)
			val vOther = thread {
				vReady.addAndFetch(1)
				while (vReady.load() < 2) {}
				vWeak.store(null)
			}
			vReady.addAndFetch(1)
			while (vReady.load() < 2) {}
			vTarget.store(null)
			vOther.join()
			vEnded++
		}
		assertEquals(3_000, vEnded)
	}
}
