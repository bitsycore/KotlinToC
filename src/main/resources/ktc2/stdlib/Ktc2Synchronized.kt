package kotlin

import ktc2.internal.monitorEnter
import ktc2.internal.monitorExit

/**
 * `kotlin.synchronized`, which the library's own code uses. Kotlin has it on the JVM only: a
 * program is given `ktc2.concurrent.synchronized` instead, and told so if it uses this one.
 */
public inline fun <R> synchronized(lock: Any, block: () -> R): R {
	monitorEnter(lock)
	try {
		return block()
	} finally {
		monitorExit(lock)
	}
}
