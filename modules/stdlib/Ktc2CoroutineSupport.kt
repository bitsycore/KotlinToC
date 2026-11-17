@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package ktc2.internal

import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.cinterop.internal.cExpr

/*
ktc2 platform layer: what a coroutine is made of, as on Kotlin/Native. The compiler turns every
`suspend` function that suspends other than in its last call into a class that extends
[ContinuationImpl] (its parameters, its `label` and its locals are fields), and every `suspend`
lambda into one too; the body is the class's `invokeSuspend`, a state machine. [BaseContinuationImpl]
runs those state machines: `resumeWith` calls `invokeSuspend` and hands the result on to the
continuation that waits for it.

A coroutine made by `sequence { }`, `iterator { }` or `DeepRecursiveFunction` is driven by one
object: it is the receiver of the lambda and its completion, it holds the coroutine while it waits
and resumes it. Held by the coroutine in turn, it would never go: there is no cycle collector. So
the coroutine refers to its driver without counting ([driver]), and so do the frames of the
`suspend` functions it calls that are handed the driver. The scope of such a builder restricts
suspension (`@RestrictsSuspension`): only the driver's own functions can take the coroutine's
continuation, so the driver is there as long as the coroutine runs.
*/

// ==================
// MARK: State machines
// ==================

/** A coroutine's state machine, or what stands for one: run by [resumeWith]. */
public abstract class BaseContinuationImpl(completion: Continuation<Any?>?) : Continuation<Any?> {

	/** What waits for the result, when it is not the [driver]. */
	private var mCompletion: Continuation<Any?>? = completion

	/** The address of the object that drives this coroutine, not counted; 0 for none. A frame inherits it from the one it returns to. */
	public var driver: Long = (completion as? BaseContinuationImpl)?.driver ?: 0L

	/** True when the completion is the [driver] itself. */
	private var mCompletesDriver = false

	/** What waits for the result of this state machine. */
	public val completion: Continuation<Any?>?
		get() = if (mCompletesDriver) driverObject(driver) as Continuation<Any?>? else mCompletion

	/** Makes [inDriver], which is the completion, the driver of this coroutine: it is no longer counted. */
	public fun completesDriver(inDriver: Any) {
		driver = addressOf(inDriver)
		mCompletesDriver = true
		mCompletion = null
	}

	public final override fun resumeWith(result: Result<Any?>) {
		// The loop takes the place of the call of `resumeWith` of each completion in turn: no stack for a long chain.
		var vCurrent = this
		var vParam = result
		while (true) {
			val vCompletion = vCurrent.completion!!
			val vOutcome: Result<Any?> = try {
				val vValue = vCurrent.invokeSuspend(vParam)
				if (vValue === COROUTINE_SUSPENDED) return
				Result.success(vValue)
			} catch (vException: Throwable) {
				Result.failure(vException)
			}
			vCurrent.releaseIntercepted()
			if (vCompletion is BaseContinuationImpl) {
				vCurrent = vCompletion
				vParam = vOutcome
			} else {
				vCompletion.resumeWith(vOutcome)
				return
			}
		}
	}

	/** The body of the coroutine: runs it from where it waits, with the result it waited for. */
	public abstract fun invokeSuspend(result: Result<Any?>): Any?

	public open fun releaseIntercepted() {}

	/** A new coroutine of a `suspend` lambda without parameters, waited for by [completion]. */
	public open fun create(completion: Continuation<*>): Continuation<Unit> =
		throw UnsupportedOperationException("create(Continuation) has not been overridden")

	/** A new coroutine of a `suspend` lambda with one parameter or a receiver, waited for by [completion]. */
	public open fun create(value: Any?, completion: Continuation<*>): Continuation<Unit> =
		throw UnsupportedOperationException("create(Any?;Continuation) has not been overridden")

	override fun toString(): String = "Continuation"
}

/** A state machine with the context of its completion. */
public abstract class ContinuationImpl(completion: Continuation<Any?>?) : BaseContinuationImpl(completion) {

	private val mContext: CoroutineContext? = completion?.context

	/** What [intercepted] gave, when an interceptor made it. */
	private var mIntercepted: Continuation<Any?>? = null

	override val context: CoroutineContext
		get() = mContext ?: EmptyCoroutineContext

	/** This continuation as the interceptor of its context wants it resumed (itself when there is none). */
	public fun intercepted(): Continuation<Any?> {
		mIntercepted?.let { return it }
		val vInterceptor = context[ContinuationInterceptor] ?: return this
		val vIntercepted = vInterceptor.interceptContinuation(this)
		// Kept only when it is another object: kept, this one would hold itself.
		if (vIntercepted !== this) mIntercepted = vIntercepted
		return vIntercepted
	}

	override fun releaseIntercepted() {
		val vIntercepted = mIntercepted ?: return
		mIntercepted = null
		context[ContinuationInterceptor]!!.releaseInterceptedContinuation(vIntercepted)
	}
}

/**
 * The coroutine of a function value that is no `suspend` lambda of the program (a reference to a
 * `suspend` function): started, it calls the function once with itself as the continuation.
 */
private class FunctionCoroutine(completion: Continuation<Any?>, private val mStart: (Continuation<Any?>) -> Any?) : ContinuationImpl(completion) {
	private var mLabel = 0

	override fun invokeSuspend(result: Result<Any?>): Any? = when (mLabel) {
		0 -> {
			mLabel = 1
			result.getOrThrow()
			mStart(this)
		}
		1 -> {
			mLabel = 2
			result.getOrThrow()
		}
		else -> error("This coroutine had already completed")
	}
}

/** A coroutine that calls [inStart] when it is resumed the first time. */
public fun coroutineOfFunction(inCompletion: Continuation<*>, inStart: (Continuation<Any?>) -> Any?): Continuation<Unit> =
	FunctionCoroutine(inCompletion as Continuation<Any?>, inStart) as Continuation<Unit>

// ==================
// MARK: Helpers of the generated code
// ==================

/** The value a coroutine waited for, or the exception it is resumed with, thrown. */
public fun coroutineResult(inResult: Result<Any?>): Any? = inResult.getOrThrow()

/** The context of the coroutine [inContinuation] belongs to: what `coroutineContext` reads. */
public fun coroutineContextOf(inContinuation: Any): CoroutineContext = (inContinuation as Continuation<*>).context

/** Makes [inDriver], which is also the completion of the new coroutine [inCoroutine], its driver: see [BaseContinuationImpl.completesDriver]. */
public fun coroutineDrivenBy(inCoroutine: Any, inDriver: Any) {
	(inCoroutine as BaseContinuationImpl).completesDriver(inDriver)
}

/** True when [inValue] is the driver of the coroutine that [inCompletion] belongs to: a frame then does not count it. */
public fun isCoroutineDriver(inValue: Any?, inCompletion: Any): Boolean = isDriver(inValue, driverOf(inCompletion as Continuation<*>))

/** The address of [inObject], as [BaseContinuationImpl.driver] keeps it. */
public fun addressOf(inObject: Any): Long = cExpr("(int64_t)(intptr_t)$0", inObject)

/** The object at [inAddress], counted for the caller. */
public fun driverObject(inAddress: Long): Any? = cExpr("ktc2_retain((void*)(intptr_t)$0)", inAddress)

/** The driver of the coroutine [inCompletion] belongs to, which a new frame for it inherits; 0 for none. */
public fun driverOf(inCompletion: Continuation<*>): Long = (inCompletion as? BaseContinuationImpl)?.driver ?: 0L

/** True when [inValue] is the driver at [inDriver]. */
public fun isDriver(inValue: Any?, inDriver: Long): Boolean = inDriver != 0L && inValue != null && addressOf(inValue) == inDriver
