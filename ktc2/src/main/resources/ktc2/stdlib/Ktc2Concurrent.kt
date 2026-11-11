@file:Suppress("UNUSED_PARAMETER")

package kotlin.concurrent

/**
 * Creates a thread that runs [block], started at once unless [start] is false.
 * [isDaemon], [name] and [priority] are accepted and ignored.
 */
public fun thread(
	start: Boolean = true,
	isDaemon: Boolean = false,
	name: String? = null,
	priority: Int = -1,
	block: () -> Unit,
): Thread {
	val vThread = Thread(block)
	if (start) vThread.start()
	return vThread
}
