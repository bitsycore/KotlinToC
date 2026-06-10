package test

// Integration test for the setjmp/longjmp exception system:
// throw/catch, multi-catch ordering, interface catches, finally semantics,
// nested try, rethrow, arena reuse + growth, propagation across frames,
// return-from-try, and loops.

class ParseError(override val message: String, val pos: Int) : Exception
class NetError(override val message: String, val code: Int) : Exception

// ==================
// MARK: Throw helpers
// ==================

fun mayThrow(x: Int): Int {
    if (x == 1) throw ParseError("parse fail", 11)
    if (x == 2) throw NetError("net fail", 42)
    if (x == 3) throw RuntimeException("generic")
    return x * 10
}

// Propagation through frames with no try anywhere in between.
fun depth3(x: Int): Int { return mayThrow(x) }
fun depth2(x: Int): Int { return depth3(x) }
fun depth1(x: Int): Int {
    try {
        return depth2(x)
    } catch (e: ParseError) {
        return -e.pos
    }
}

// ==================
// MARK: Scenarios
// ==================

// Multi-catch ordering: concrete classes first, interface as the catch-all.
fun catchCode(x: Int): Int {
    try {
        return mayThrow(x)
    } catch (e: ParseError) {
        return 1000 + e.pos
    } catch (e: NetError) {
        return 2000 + e.code
    } catch (e: Exception) {
        return 3000
    }
}

// finally runs exactly once on both the normal and the exceptional path.
fun finallyOrder(x: Int): Int {
    var order = 0
    try {
        order = order * 10 + 1
        if (x == 1) throw RuntimeException("boom")
        order = order * 10 + 2
    } catch (e: RuntimeException) {
        order = order * 10 + 3
    } finally {
        order = order * 10 + 4
    }
    return order
}

// Nested try: catch in the inner, throw a NEW exception from the catch body
// (arena reuse), with a template message built from the caught one (deep copy),
// inner finally still runs, outer catches the replacement.
fun nestedRethrowNew(): Int {
    var t = 0
    try {
        try {
            throw ParseError("inner", 5)
        } catch (e: ParseError) {
            t += 1
            throw NetError("wrapped: ${e.message}", 7)
        } finally {
            t += 10
        }
    } catch (e: NetError) {
        if (e.message != "wrapped: inner") return -1
        t += 100
    }
    return t   // 111
}

// Plain rethrow of the caught binding, caught again by the enclosing try.
fun rethrowCaught(): Int {
    try {
        try {
            throw ParseError("again", 3)
        } catch (e: ParseError) {
            throw e
        }
    } catch (e: ParseError) {
        if (e.message == "again") return e.pos
        return -1
    }
    return -2
}

// An unhandled exception propagates THROUGH a try/finally (no catch) — the
// finally must run on the way out.
fun propagateThroughFinally(): Int {
    var t = 0
    try {
        try {
            throw RuntimeException("p")
        } finally {
            t += 1
        }
    } catch (e: RuntimeException) {
        t += 10
    }
    return t   // 11
}

// Catch by the root Throwable interface; message read through the vtable.
fun catchAsThrowable(): Int {
    try {
        throw IllegalStateException("ise")
    } catch (t: Throwable) {
        if (t.message == "ise") return 1
        return 2
    }
    return 3
}

// `when (e)` + is/smart-cast on an interface-typed caught binding.
fun whenOnCaught(): Int {
    try {
        throw NetError("w", 9)
    } catch (e: Exception) {
        when (e) {
            is NetError    -> return e.code
            is ParseError  -> return -1
            else           -> return -2
        }
    }
    return -3
}

// Exceptions thrown and caught repeatedly inside a loop (frame per iteration).
fun loopThrows(): Int {
    var count = 0
    for (i in 0..4) {
        try {
            if (i % 2 == 0) throw ParseError("even", i)
            count += 1
        } catch (e: ParseError) {
            count += 100
        }
    }
    return count   // 3 caught + 2 normal = 302
}

// A message far larger than the initial arena (256 bytes) — growth + integrity.
fun bigMessage(): Int {
    val a = "0123456789012345678901234567890123456789"   // 40 chars
    val msg = "$a$a$a$a$a$a$a$a$a$a"                     // 400 chars
    try {
        throw RuntimeException("$msg$msg")               // 800 chars
    } catch (e: RuntimeException) {
        return e.message.length
    }
    return -1
}

// Unused catch binding — no arena copy is emitted, matching must still work.
fun unusedBinding(): Int {
    try {
        throw RuntimeException("x")
    } catch (e: RuntimeException) {
        return 7
    }
    return 0
}

// Custom payload fields survive the arena round-trip alongside the message.
fun payloadIntact(): Int {
    try {
        throw NetError("payload", 1234)
    } catch (e: NetError) {
        if (e.message == "payload" && e.code == 1234) return 1
    }
    return 0
}

// ==================
// MARK: Main
// ==================

fun main() {
    if (catchCode(0) != 0)     error("FAIL catchCode(0)=${catchCode(0)}")
    if (catchCode(5) != 50)    error("FAIL catchCode(5)=${catchCode(5)}")
    if (catchCode(1) != 1011)  error("FAIL catchCode(1)=${catchCode(1)}")
    if (catchCode(2) != 2042)  error("FAIL catchCode(2)=${catchCode(2)}")
    if (catchCode(3) != 3000)  error("FAIL catchCode(3)=${catchCode(3)}")
    println("multi-catch: OK")

    if (depth1(1) != -11)      error("FAIL depth1(1)=${depth1(1)}")
    if (depth1(4) != 40)       error("FAIL depth1(4)=${depth1(4)}")
    println("deep propagation: OK")

    if (finallyOrder(0) != 124) error("FAIL finallyOrder(0)=${finallyOrder(0)}")
    if (finallyOrder(1) != 134) error("FAIL finallyOrder(1)=${finallyOrder(1)}")
    println("finally ordering: OK")

    if (nestedRethrowNew() != 111) error("FAIL nestedRethrowNew=${nestedRethrowNew()}")
    if (rethrowCaught() != 3)      error("FAIL rethrowCaught=${rethrowCaught()}")
    if (propagateThroughFinally() != 11) error("FAIL propagateThroughFinally=${propagateThroughFinally()}")
    println("nested/rethrow: OK")

    if (catchAsThrowable() != 1)  error("FAIL catchAsThrowable=${catchAsThrowable()}")
    if (whenOnCaught() != 9)      error("FAIL whenOnCaught=${whenOnCaught()}")
    println("interface catch: OK")

    if (loopThrows() != 302)      error("FAIL loopThrows=${loopThrows()}")
    if (bigMessage() != 800)      error("FAIL bigMessage=${bigMessage()}")
    if (unusedBinding() != 7)     error("FAIL unusedBinding=${unusedBinding()}")
    if (payloadIntact() != 1)     error("FAIL payloadIntact=${payloadIntact()}")
    println("loops/arena/payload: OK")

    println("ALL OK")
}
