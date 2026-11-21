import kotlin.coroutines.*

// The cycles a program makes through coroutines and lambdas that it could avoid with the API as it is: ktc2 warns at
// compile time, saying what to write instead (UserCycles.warnings; PLAN.md, "Who answers a cycle", rule 4). They are
// made here as written, and left at the end (UserCycles.cycles). What the warnings ask for is shown too, unwarned.

fun start(block: suspend () -> Unit) {
	block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
}

/** A continuation kept in a variable its own coroutine captures, never cleared. */
fun keptInVariable(): String {
	var vKept: Continuation<String>? = null
	var vResult = ""
	start {
		vResult = suspendCoroutine { vKept = it }
	}
	vKept!!.resume("resumed")
	return vResult
}

/** The same, cleared once resumed: no warning. */
fun clearedOnceResumed(): String {
	var vKept: Continuation<String>? = null
	var vResult = ""
	start {
		vResult = suspendCoroutine { vKept = it }
	}
	val vContinuation = vKept!!
	vKept = null
	vContinuation.resume("cleared")
	return vResult
}

class Walker {
	var steps = 0

	/** A DeepRecursiveFunction whose lambda uses the object that keeps it. */
	val depth: DeepRecursiveFunction<Int, Int> = DeepRecursiveFunction { vLeft ->
		steps++
		if (vLeft == 0) 0 else callRecursive(vLeft - 1) + 1
	}
}

/** The same, through a weak reference of the object that the lambda captures (a local, not a property of it): no warning. */
class WeakWalker {
	var steps = 0
	val depth: DeepRecursiveFunction<Int, Int> = run {
		val vSelf = ktc2.WeakRef(this)
		DeepRecursiveFunction { vLeft ->
			vSelf.get()?.let { it.steps++ }
			if (vLeft == 0) 0 else callRecursive(vLeft - 1) + 1
		}
	}
}

fun main() {
	println(keptInVariable())
	println(clearedOnceResumed())
	val vWalker = Walker()
	println("${vWalker.depth(5)} in ${vWalker.steps} steps")
	val vWeak = WeakWalker()
	println("${vWeak.depth(3)} in ${vWeak.steps} steps")
}
