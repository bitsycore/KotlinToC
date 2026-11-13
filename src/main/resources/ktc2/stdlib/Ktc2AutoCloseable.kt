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
 * `close` throws while the block's own exception is on its way is added to that one as
 * suppressed: the block's is the one that goes on.
 */
@OptIn(kotlin.contracts.ExperimentalContracts::class)
public actual inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
	kotlin.contracts.contract {
		callsInPlace(block, kotlin.contracts.InvocationKind.EXACTLY_ONCE)
	}
	var vFailure: Throwable? = null
	try {
		return block(this)
	} catch (inError: Throwable) {
		vFailure = inError
		throw inError
	} finally {
		if (this != null) {
			if (vFailure == null) {
				close()
			} else {
				try {
					close()
				} catch (inError: Throwable) {
					vFailure.addSuppressed(inError)
				}
			}
		}
	}
}
