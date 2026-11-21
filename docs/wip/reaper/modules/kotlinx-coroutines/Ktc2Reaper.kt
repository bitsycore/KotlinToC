package kotlinx.coroutines

import kotlin.native.concurrent.ThreadLocal

/**
 * ktc2: the reaper of the jobs of this thread that ended with an exception (`PLAN.md`, "Who answers a cycle", rule 3).
 *
 * Such a job may be left in cycles with what it ended with: its final state names the exception, which names the job, and
 * the job's channel keeps the same exception as its cause of closing. The checks a release makes start from the object
 * whose count came down, while something (a test, a caller) still holds part of the group; nothing comes down later. So
 * the job registers here when it ends (`JobSupport.kt.edit`), through its weak cell, which keeps nothing, and the loop of
 * this thread sweeps between events and when it ends (`Builders.native.kt.edit`): each job is checked with its group
 * (`ktc2.internal.collectGroupOf`, the runtime's check of declared cycles and rings), and freed once nothing outside holds
 * any of it. A job still held stays registered for the next sweep.
 *
 * It answers only the library's own objects: the group the runtime checks is made of the classes the module declares
 * (`cycles`, `rings`), and a cycle through an object of the program is never cut.
 */
@ThreadLocal
internal object Ktc2Reaper {
	private val mCells = ArrayList<Any?>()

	/** [job] ended with an exception: checked at the next sweeps until it is freed. */
	fun register(job: Any) {
		mCells.add(ktc2.internal.weakCell(job))
	}

	/** Frees what the registered jobs left that nothing holds any more. */
	fun sweep() {
		if (mCells.isEmpty()) return
		var vIndex = mCells.size - 1
		while (vIndex >= 0) {
			if (ktc2.internal.collectGroupOf(mCells[vIndex])) mCells.removeAt(vIndex)
			vIndex--
		}
	}
}

internal actual fun ktc2Register(job: Any) = Ktc2Reaper.register(job)

internal actual fun ktc2Sweep() = Ktc2Reaper.sweep()
