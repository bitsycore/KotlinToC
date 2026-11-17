@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kotlinx.atomicfu.locks

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CPointer
import ktc2.atomicfu.*

/*
ktc2's part of atomicfu: what atomicfu gives each system of Kotlin/Native apart (its mingwMain,
linuxMain, appleMain), given here once for every system ktc2 compiles for. Each lock with its
condition is an atomicfu_Monitor of atomicfu.h, over Win32 or pthreads.
*/

private fun newMonitor(): CPointer<atomicfu_Monitor> = atomicfu_new() ?: throw IllegalStateException("No memory for a lock")

/** A lock and its condition, for the pool of [SynchronizedObject]'s locks. */
internal actual class NativeMutexNode {
	internal actual var next: NativeMutexNode? = null

	private val mMonitor = newMonitor()

	public actual fun lock() {
		atomicfu_lock(mMonitor)
	}

	public actual fun unlock() {
		atomicfu_unlock(mMonitor)
	}

	internal actual fun wait(lockOwner: Long) {
		atomicfu_wait(mMonitor)
	}

	internal actual fun notify() {
		atomicfu_notify(mMonitor)
	}

	internal actual fun dispose() {
		atomicfu_free(mMonitor)
	}
}

private val threadCounter = atomic(0L)

internal actual fun createThreadId(): Long = threadCounter.incrementAndGet()

/** What a parked thread waits on. */
internal actual class ParkingData(val monitor: CPointer<atomicfu_Monitor>)

internal actual object ParkingDelegator {
	actual fun createRef(): ParkingData = ParkingData(newMonitor())

	actual inline fun wait(ref: ParkingData, shouldWait: () -> Boolean) {
		atomicfu_lock(ref.monitor)
		try {
			if (shouldWait()) atomicfu_wait(ref.monitor)
		} finally {
			atomicfu_unlock(ref.monitor)
		}
	}

	actual inline fun timedWait(ref: ParkingData, nanos: Long, shouldWait: () -> Boolean) {
		atomicfu_lock(ref.monitor)
		try {
			if (shouldWait()) atomicfu_wait_nanos(ref.monitor, nanos)
		} finally {
			atomicfu_unlock(ref.monitor)
		}
	}

	actual fun wake(ref: ParkingData) {
		atomicfu_lock(ref.monitor)
		try {
			atomicfu_notify(ref.monitor)
		} finally {
			atomicfu_unlock(ref.monitor)
		}
	}

	actual fun destroyRef(ref: ParkingData) {
		atomicfu_free(ref.monitor)
	}
}
