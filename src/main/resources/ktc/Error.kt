package ktc

/**
 * Throws [IllegalArgumentException] if the [value] is false.
 */
inline fun require(value: Boolean): Unit {
    if (!value) {
        throw IllegalArgumentException("Failed requirement.")
    }
}

/**
 * Throws [IllegalArgumentException] with the result of calling [lazyMessage] if the [value] is false.
 */
inline fun require(value: Boolean, lazyMessage: () -> String): Unit {
    if (!value) {
        val message = lazyMessage()
        throw IllegalArgumentException(message)
    }
}

/**
 * Throws [IllegalArgumentException] if the [value] is null. Otherwise returns the not null value.
 */
inline fun <T> requireNotNull(value: T?): T {
    if (value == null) {
        throw IllegalArgumentException("Required value was null.")
    } else {
        return value
    }
}

/**
 * Throws [IllegalArgumentException] with the result of calling [lazyMessage] if the [value] is null.
 * Otherwise returns the not null value.
 */
inline fun <T> requireNotNull(value: T?, lazyMessage: () -> String): T {
    if (value == null) {
        val message = lazyMessage()
        throw IllegalArgumentException(message)
    } else {
        return value
    }
}

/**
 * Throws [IllegalStateException] if the [value] is false.
 */
inline fun check(value: Boolean): Unit {
    if (!value) {
        throw IllegalStateException("Check failed.")
    }
}

/**
 * Throws [IllegalStateException] with the result of calling [lazyMessage] if the [value] is false.
 */
inline fun check(value: Boolean, lazyMessage: () -> String): Unit {
    if (!value) {
        val message = lazyMessage()
        throw IllegalStateException(message)
    }
}

/**
 * Throws [IllegalStateException] if the [value] is null. Otherwise
 * returns the not null value.
 */
inline fun <T> checkNotNull(value: T?): T {
    if (value == null) {
        throw IllegalStateException("Required value was null.")
    } else {
        return value
    }
}

/**
 * Throws [IllegalStateException] with the result of calling [lazyMessage] if the [value] is null.
 * Otherwise returns the not null value.
 */
inline fun <T> checkNotNull(value: T?, lazyMessage: () -> String): T {
    if (value == null) {
        val message = lazyMessage()
        throw IllegalStateException(message)
    } else {
        return value
    }
}

/**
 * Throws [IllegalStateException] with the given [message].
 * Uncaught it prints a stack trace and exits with failure.
 */
fun error(message: String): Nothing {
    throw IllegalStateException(message)
}

/**
 * Unconditional hard failure OUTSIDE the exception system: prints a stack
 * trace with the caller's file:line and exits - not catchable. Use for
 * assert-style checks (tests, invariants) where unwinding is undesirable.
 */
fun fatalError(message: String, file: String = Macro.FILE, line: Int = Macro.LINE): Nothing {
    C.ktc_core_stacktrace_print(message.cPtr, message.len, file.cPtr, file.len, line)
    C.exit(C.EXIT_FAILURE);
}
