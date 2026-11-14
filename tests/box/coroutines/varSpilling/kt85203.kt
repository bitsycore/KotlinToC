// WITH_STDLIB
// WITH_COROUTINES

import helpers.*
import kotlin.coroutines.*

class SecondLandingpad

class Reproducer {
    suspend fun firstLandingpad() {}
    fun loadInstancePointer() {}

    // More complex case checking, that `loadInstancePointer()` can be called at runtime is in kt85203_2.kt
    suspend inline fun runImpl(): Any? {
        try {
            try {
                firstLandingpad()
            } catch (_: Throwable) {}
            return SecondLandingpad()
        } catch (_: Throwable) {
            loadInstancePointer()
            return null
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
        if (reproducer.run() is SecondLandingpad) {
            result = "OK"
        }
    }
    return result
}
