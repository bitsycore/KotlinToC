import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

// A coroutine resumed inside the call that suspends it (as a dispatcher that runs what it is given at once does),
// with an argument made for that call and released after it: the run that goes on from the resume releases it,
// and the run that made the call only returns.

class Box(val name: String) {
	override fun toString(): String = "Box($name)"
}

/** Resumes its caller with [box]'s name before it returns COROUTINE_SUSPENDED. */
suspend fun handBack(box: Box): String = suspendCoroutineUninterceptedOrReturn { continuation ->
	continuation.resume(box.name + "!")
	COROUTINE_SUSPENDED
}

fun make(index: Int): Box = Box("b$index")

suspend fun many(count: Int): String {
	val vParts = ArrayList<String>()
	for (vIndex in 0 until count) {
		vParts += handBack(make(vIndex))
		vParts += handBack(Box("x$vIndex")).uppercase()
	}
	return vParts.joinToString()
}

/** Something to pull a name from, through a call that resumes its caller at once. */
interface Source {
	suspend fun pull(): String
}

class Named(val name: String) : Source {
	override suspend fun pull(): String = handBack(Box(name))
}

/** Each source of [sources], read from the array into a temporary that the call is its last use of. */
suspend fun pullAll(sources: Array<Source>): String {
	val vParts = ArrayList<String>()
	for (vIndex in sources.indices) {
		vParts += sources[vIndex].pull()
	}
	return vParts.joinToString("+")
}

suspend fun nested(): String {
	val vPulled = pullAll(arrayOf(Named("p"), Named("q"), Named("r")))

	val vFirst = many(2)
	val vBox = make(9)
	return vFirst + " / " + handBack(vBox) + " " + vBox + " / " + vPulled
}

fun main() {
	var vOut: Result<String>? = null
	suspend { nested() }.startCoroutine(Continuation(EmptyCoroutineContext) { vOut = it })
	println(vOut!!.getOrThrow())
}
