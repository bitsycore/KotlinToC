package ktc.std


/**
 * [error] if the [value] is false.
 */
inline fun require(value: Boolean): Unit {
    if (!value) {
        error("Failed requirement.")
    }
}

/**
 * [error] with the result of calling [lazyMessage] if the [value] is false.
 */
inline fun require(value: Boolean, lazyMessage: () -> String): Unit {
    if (!value) {
        val message = lazyMessage()
        error(message)
    }
}

/**
 * [error] if the [value] is null. Otherwise returns the not null value.
 */
inline fun <T> requireNotNull(value: T?): T {
    if (value == null) {
        error("Required value was null.")
    } else {
        return value
    }
}

/**
 * [error] with the result of calling [lazyMessage] if the [value] is null. Otherwise
 * returns the not null value.
 */
inline fun <T> requireNotNull(value: T?, lazyMessage: () -> String): T {
    if (value == null) {
        val message = lazyMessage()
        error(message)
    } else {
        return value
    }
}

/**
 * [error] if the [value] is false.
 */
inline fun check(value: Boolean): Unit {
    if (!value) {
        error("Check failed.")
    }
}

/**
 * [error] with the result of calling [lazyMessage] if the [value] is false.
 */
inline fun check(value: Boolean, lazyMessage: () -> String): Unit {
    if (!value) {
        val message = lazyMessage()
        error(message)
    }
}

/**
 * [error] if the [value] is null. Otherwise
 * returns the not null value.
 */
inline fun <T> checkNotNull(value: T?): T {
    if (value == null) {
        error("Required value was null.")
    } else {
        return value
    }
}

/**
 * [error] with the result of calling [lazyMessage] if the [value] is null. Otherwise
 * returns the not null value.
 */
inline fun <T> checkNotNull(value: T?, lazyMessage: () -> String): T {
    if (value == null) {
        val message = lazyMessage()
        error(message)
    } else {
        return value
    }
}

/**
 * Exit program with failure and write stacktrace before with the given [message].
 */
fun error(message: String): Nothing {
    c.ktc_core_stacktrace_print(message.ptr, message.len);
    c.exit(c.EXIT_FAILURE);
}
