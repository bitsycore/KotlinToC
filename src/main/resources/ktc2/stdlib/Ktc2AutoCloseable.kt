@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_WITHOUT_EXPECT", "NOTHING_TO_INLINE")

package kotlin

/*
ktc2 platform layer for `AutoCloseable` and `use`: as on Kotlin/Native, an interface of the
library itself (on the JVM it is java.lang's).
*/

public actual interface AutoCloseable {
	public actual fun close()
}

public actual inline fun AutoCloseable(crossinline closeAction: () -> Unit): AutoCloseable = object : AutoCloseable {
	override fun close() = closeAction()
}

/**
 * Runs [block] with this resource and closes it, whether the block ends or throws. What
 * `close` throws while the block's own exception is on its way is dropped: the block's is the
 * one that goes on.
 */
public actual inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
	var vFailed = false
	try {
		return block(this)
	} catch (inError: Throwable) {
		vFailed = true
		throw inError
	} finally {
		if (this != null) {
			if (!vFailed) {
				close()
			} else {
				try {
					close()
				} catch (inError: Throwable) {
				}
			}
		}
	}
}
