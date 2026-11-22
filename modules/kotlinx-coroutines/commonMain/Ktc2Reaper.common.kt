package kotlinx.coroutines

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// ktc2: what the library's common code calls of the reaper of jobs that ended with an exception (Ktc2Reaper.kt).

/** [job] ended with an exception: the reaper of this thread checks it once nothing else holds any of what it left. */
internal expect fun ktc2Register(job: Any)

/** Frees what the jobs of this thread that ended with an exception left that nothing holds any more. */
internal expect fun ktc2Sweep()

/** ktc2: what a scope holds in the place of its caller's continuation once it has let go of it (Scopes.kt.edit): resumed by nothing. */
internal object Ktc2DoneContinuation : Continuation<Any?> {
    override val context: CoroutineContext get() = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) {}
}

@Suppress("UNCHECKED_CAST")
internal fun <T> ktc2DoneContinuation(): Continuation<T> = Ktc2DoneContinuation as Continuation<T>
