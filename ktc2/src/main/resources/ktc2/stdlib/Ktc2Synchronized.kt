package kotlin

import ktc2.internal.monitorEnter
import ktc2.internal.monitorExit

/** Runs [block] while holding the monitor of [lock]. */
public inline fun <R> synchronized(lock: Any, block: () -> R): R {
	monitorEnter(lock)
	try {
		return block()
	} finally {
		monitorExit(lock)
	}
}
