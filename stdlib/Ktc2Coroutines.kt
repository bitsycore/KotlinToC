@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.coroutines

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.CoroutineSingletons

/*
ktc2 platform layer: the continuation `suspendCoroutine` hands its block, as on Kotlin/Native. It
tells whether the block resumed it before returning (the function then does not suspend) or after
(the coroutine waits, and is resumed through it).
*/

@PublishedApi
internal actual class SafeContinuation<in T> internal actual constructor(
	private val delegate: Continuation<T>,
	initialResult: Any?,
) : Continuation<T> {

	@PublishedApi
	internal actual constructor(delegate: Continuation<T>) : this(delegate, CoroutineSingletons.UNDECIDED)

	public actual override val context: CoroutineContext
		get() = delegate.context

	/** `UNDECIDED`, `COROUTINE_SUSPENDED`, `RESUMED`, or the result given before the block returned (a `Result`). */
	private var mResult: Any? = initialResult

	public actual override fun resumeWith(result: Result<T>) {
		val vCurrent = mResult
		when {
			vCurrent === CoroutineSingletons.UNDECIDED -> mResult = result
			vCurrent === COROUTINE_SUSPENDED -> {
				mResult = CoroutineSingletons.RESUMED
				delegate.resumeWith(result)
			}
			else -> throw IllegalStateException("Already resumed")
		}
	}

	@PublishedApi
	internal actual fun getOrThrow(): Any? {
		val vResult = mResult
		if (vResult === CoroutineSingletons.UNDECIDED) {
			mResult = COROUTINE_SUSPENDED
			return COROUTINE_SUSPENDED
		}
		if (vResult === CoroutineSingletons.RESUMED || vResult === COROUTINE_SUSPENDED) return COROUTINE_SUSPENDED
		return (vResult as Result<Any?>).getOrThrow()
	}
}
