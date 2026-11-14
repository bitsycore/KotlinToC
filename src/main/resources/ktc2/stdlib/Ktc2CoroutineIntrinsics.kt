@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package kotlin.coroutines.intrinsics

import kotlin.coroutines.Continuation
import ktc2.internal.BaseContinuationImpl
import ktc2.internal.ContinuationImpl
import ktc2.internal.coroutineOfFunction

/*
ktc2 platform layer: the intrinsics of `kotlin.coroutines`, as on Kotlin/Native. A `suspend`
function value is a `FunctionN` that takes the continuation last and returns the result or
`COROUTINE_SUSPENDED`; a `suspend` lambda is also a [BaseContinuationImpl] that `create`s its
coroutines. `suspendCoroutineUninterceptedOrReturn` and `coroutineContext` are the compiler's.
*/

@Suppress("NOTHING_TO_INLINE")
public actual inline fun <T> (suspend () -> T).startCoroutineUninterceptedOrReturn(completion: Continuation<T>): Any? =
	(this as Function1<Continuation<T>, Any?>).invoke(completion)

@Suppress("NOTHING_TO_INLINE")
public actual inline fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(receiver: R, completion: Continuation<T>): Any? =
	(this as Function2<R, Continuation<T>, Any?>).invoke(receiver, completion)

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(receiver: R, param: P, completion: Continuation<T>): Any? =
	(this as Function3<R, P, Continuation<T>, Any?>).invoke(receiver, param, completion)

public actual fun <T> (suspend () -> T).createCoroutineUnintercepted(completion: Continuation<T>): Continuation<Unit> =
	if (this is BaseContinuationImpl) {
		create(completion)
	} else {
		coroutineOfFunction(completion) { (this as Function1<Continuation<T>, Any?>).invoke(it as Continuation<T>) }
	}

public actual fun <R, T> (suspend R.() -> T).createCoroutineUnintercepted(receiver: R, completion: Continuation<T>): Continuation<Unit> =
	if (this is BaseContinuationImpl) {
		create(receiver, completion)
	} else {
		coroutineOfFunction(completion) { (this as Function2<R, Continuation<T>, Any?>).invoke(receiver, it as Continuation<T>) }
	}

public actual fun <T> Continuation<T>.intercepted(): Continuation<T> =
	(this as? ContinuationImpl)?.intercepted() as Continuation<T>? ?: this
