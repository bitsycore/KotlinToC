package test

// Integration test for the setjmp/longjmp exception system:
// throw/catch, multi-catch ordering, interface catches, finally semantics,
// nested try, rethrow, arena reuse + growth, propagation across frames,
// return-from-try, and loops.

class ParseError(override val message: String, val pos: Int) : Exception
class NetError(override val message: String, val code: Int) : Exception

// Sub-interface chain: FileGone is an Exception only transitively (via IoError).
interface IoError : Exception
class FileGone(override val message: String) : IoError

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

// An unhandled exception propagates THROUGH a try/finally (no catch) - the
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

// A message far larger than the initial arena (256 bytes) - growth + integrity.
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

// Unused catch binding - no arena copy is emitted, matching must still work.
fun unusedBinding(): Int {
    try {
        throw RuntimeException("x")
    } catch (e: RuntimeException) {
        return 7
    }
    return 0
}

// A single `catch (e: Exception)` must catch EVERY exception subtype: user
// classes, stdlib classes, and classes implementing a sub-interface of
// Exception (FileGone via IoError) - narrowed back with `is`.
fun catchJustException(x: Int): Int {
    try {
        if (x == 1) throw ParseError("p", 1)
        if (x == 2) throw RuntimeException("r")
        if (x == 3) throw FileGone("f")
        return 0
    } catch (e: Exception) {
        when (e) {
            is ParseError       -> return 1
            is RuntimeException -> return 2
            is FileGone         -> return 3
            else                -> return -1
        }
    }
    return -2
}

// Catching the sub-interface itself only matches its own implementors.
fun catchSubInterface(x: Int): Int {
    try {
        try {
            if (x == 1) throw FileGone("f")
            throw ParseError("p", 1)
        } catch (e: IoError) {
            return 10
        }
    } catch (e: Exception) {
        return 20
    }
    return 0
}

// ==================
// MARK: Stdlib throwing functions
// ==================

// error() throws a catchable IllegalStateException.
fun stdlibError(): Int {
    try {
        error("from error()")
    } catch (e: IllegalStateException) {
        if (e.message == "from error()") return 1
    }
    return 0
}

// require() throws IllegalArgumentException, check() throws IllegalStateException.
fun stdlibRequireCheck(): Int {
    var t = 0
    try {
        require(false) { "req failed" }
    } catch (e: IllegalArgumentException) {
        if (e.message == "req failed") t += 1
    }
    try {
        check(false)
    } catch (e: IllegalStateException) {
        if (e.message == "Check failed.") t += 10
    }
    return t   // 11
}

// TODO() throws NotImplementedError - a Throwable but NOT an Exception
// (mirrors Kotlin, where NotImplementedError is an Error).
fun stdlibTodo(): Int {
    try {
        try {
            TODO("later")
        } catch (e: Exception) {
            return -1   // must NOT be caught here
        }
    } catch (e: Throwable) {
        if (e.message == "An operation is not implemented: later") return 1
    }
    return 0
}

// String.toInt() parses, or throws NumberFormatException on bad input.
fun stdlibParse(): Int {
    var t = 0
    try {
        val v = "123".toInt()
        if (v == 123) t += 1
    } catch (e: NumberFormatException) {
        return -1
    }
    try {
        val v = "abc".toInt()
        t += 1000 + v   // not reached
    } catch (e: NumberFormatException) {
        t += 10
    }
    return t   // 11
}

// Runtime checks are catchable: bounds violations throw IndexOutOfBoundsException,
// dynamic null derefs throw NullPointerException (registered by the generated main).
fun readAt(arr: IntArray, i: Int): Int {
    return arr[i]
}
fun deref(p: Ref<Int>): Int = p.refValue
fun runtimeChecksCatchable(): Int {
    val arr = intArrayOf(1, 2, 3)
    var t = 0
    try {
        t = readAt(arr, 7)
        return -1
    } catch (e: IndexOutOfBoundsException) {
        if (!e.message.contains("7")) return -2
        t += 1
    }
    try {
        t = readAt(arr, -1)
        return -3
    } catch (e: RuntimeException) {     // supertype catches it too
        t += 10
    }
    val q: Ref<Int?> = null
    try {
        t = deref(q as Ref<Int>)
        return -4
    } catch (e: NullPointerException) {
        t += 100
    }
    if (readAt(arr, 1) != 2) return -5  // happy path unaffected
    return t   // 111
}

// `?: throw` - the Kotlin guard idiom; left side evaluated once, throws on null.
fun findEven(k: Int): Int? {
    if (k % 2 != 0) return null
    return k * 10
}
fun elvisThrow(): Int {
    var t = 0
    val v = findEven(4) ?: throw ParseError("no even", 1)
    if (v != 40) return -1
    try {
        val w = findEven(3) ?: throw ParseError("odd rejected", 3)
        return -2 - w
    } catch (e: ParseError) {
        if (e.message != "odd rejected" || e.pos != 3) return -3
        t = 1
    }
    return t
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
    if (catchCode(0) != 0)     fatalError("FAIL catchCode(0)=${catchCode(0)}")
    if (catchCode(5) != 50)    fatalError("FAIL catchCode(5)=${catchCode(5)}")
    if (catchCode(1) != 1011)  fatalError("FAIL catchCode(1)=${catchCode(1)}")
    if (catchCode(2) != 2042)  fatalError("FAIL catchCode(2)=${catchCode(2)}")
    if (catchCode(3) != 3000)  fatalError("FAIL catchCode(3)=${catchCode(3)}")
    println("multi-catch: OK")

    if (depth1(1) != -11)      fatalError("FAIL depth1(1)=${depth1(1)}")
    if (depth1(4) != 40)       fatalError("FAIL depth1(4)=${depth1(4)}")
    println("deep propagation: OK")

    if (finallyOrder(0) != 124) fatalError("FAIL finallyOrder(0)=${finallyOrder(0)}")
    if (finallyOrder(1) != 134) fatalError("FAIL finallyOrder(1)=${finallyOrder(1)}")
    println("finally ordering: OK")

    if (nestedRethrowNew() != 111) fatalError("FAIL nestedRethrowNew=${nestedRethrowNew()}")
    if (rethrowCaught() != 3)      fatalError("FAIL rethrowCaught=${rethrowCaught()}")
    if (propagateThroughFinally() != 11) fatalError("FAIL propagateThroughFinally=${propagateThroughFinally()}")
    println("nested/rethrow: OK")

    if (catchAsThrowable() != 1)  fatalError("FAIL catchAsThrowable=${catchAsThrowable()}")
    if (whenOnCaught() != 9)      fatalError("FAIL whenOnCaught=${whenOnCaught()}")
    println("interface catch: OK")

    if (catchJustException(0) != 0) fatalError("FAIL catchJustException(0)=${catchJustException(0)}")
    if (catchJustException(1) != 1) fatalError("FAIL catchJustException(1)=${catchJustException(1)}")
    if (catchJustException(2) != 2) fatalError("FAIL catchJustException(2)=${catchJustException(2)}")
    if (catchJustException(3) != 3) fatalError("FAIL catchJustException(3)=${catchJustException(3)}")
    if (catchSubInterface(1) != 10) fatalError("FAIL catchSubInterface(1)=${catchSubInterface(1)}")
    if (catchSubInterface(2) != 20) fatalError("FAIL catchSubInterface(2)=${catchSubInterface(2)}")
    println("catch-all Exception: OK")

    if (stdlibError() != 1)         fatalError("FAIL stdlibError=${stdlibError()}")
    if (stdlibRequireCheck() != 11) fatalError("FAIL stdlibRequireCheck=${stdlibRequireCheck()}")
    if (stdlibTodo() != 1)          fatalError("FAIL stdlibTodo=${stdlibTodo()}")
    if (stdlibParse() != 11)        fatalError("FAIL stdlibParse=${stdlibParse()}")
    println("stdlib exceptions: OK")

    if (runtimeChecksCatchable() != 111) fatalError("FAIL runtimeChecks=${runtimeChecksCatchable()}")
    println("catchable runtime checks: OK")

    if (elvisThrow() != 1) fatalError("FAIL elvisThrow=${elvisThrow()}")
    println("elvis-throw: OK")

    if (loopThrows() != 302)      fatalError("FAIL loopThrows=${loopThrows()}")
    if (bigMessage() != 800)      fatalError("FAIL bigMessage=${bigMessage()}")
    if (unusedBinding() != 7)     fatalError("FAIL unusedBinding=${unusedBinding()}")
    if (payloadIntact() != 1)     fatalError("FAIL payloadIntact=${payloadIntact()}")
    println("loops/arena/payload: OK")

    println("ALL OK")
}
