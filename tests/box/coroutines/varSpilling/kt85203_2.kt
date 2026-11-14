// WITH_STDLIB
// WITH_COROUTINES

import helpers.*
import kotlin.coroutines.*

class SecondLandingpad {
    init {
        throw Throwable()
    }
}

class Reproducer {
    private var field = "OK"

    suspend fun firstLandingpad() {}
    fun loadInstancePointer() = field

    suspend inline fun runImpl(): Any? {
        try {
            try {
                firstLandingpad()
            } catch (_: Throwable) {}
            return SecondLandingpad()
        } catch (_: Throwable) {
            return loadInstancePointer()
        }
    }

    suspend fun run() = runImpl() // inlining captures `this` into a local variable, triggering the bug in liveness analysis.
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

fun box(): String {
    var result = "FAIL"
    builder {
        val reproducer = Reproducer()
        val r = reproducer.run()
        if (r is String) {
            result = r
        }
    }
    return result
}
