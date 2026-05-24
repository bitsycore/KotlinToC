package ktc.std

/**
 * Always Exit with [NotImplementedError] stating that operation is not implemented.
 *
 * @param reason a string explaining why the implementation is missing.
 */
fun TODO(reason: String? = null) {
    if (reason == null) {
        error("(NotImplementedError) An operation is not implemented.")
    } else {
        error("(NotImplementedError) An operation is not implemented: $reason")
    }
}

/**
 * Calls the specified function [block] and returns its result.
 */
inline fun <R> run(block: () -> R): R {
    return block()
}

/**
 * Calls the specified function [block] with `this` value as its receiver and returns its result.
 */
inline fun <T, R> T.run(block: T.() -> R): R {
    return block()
}

/**
 * Calls the specified function [block] with the given [receiver] as its receiver and returns its result.
 */
inline fun <T, R> with(receiver: T, block: T.() -> R): R {
    return receiver.block()
}

/**
 * Calls the specified function [block] with `this` value as its receiver and returns `this` value.
 */
inline fun <T> T.apply(block: T.() -> Unit): T {
    block()
    return this
}

/**
 * Calls the specified function [block] with `this` value as its argument and returns `this` value.
 */
inline fun <T> T.also(block: (T) -> Unit): T {
    block(this)
    return this
}

/**
 * Calls the specified function [block] with `this` value as its argument and returns its result.
 */
inline fun <T, R> T.let(block: (T) -> R): R {
    return block(this)
}

/**
 * Returns `this` value if it satisfies the given [predicate] or `null`, if it doesn't.
 */
inline fun <T> T.takeIf(predicate: (T) -> Boolean): T? {
    return if (predicate(this)) this else null
}

/**
 * Returns `this` value if it _does not_ satisfy the given [predicate] or `null`, if it does.
 */
inline fun <T> T.takeUnless(predicate: (T) -> Boolean): T? {
    return if (!predicate(this)) this else null
}

/**
 * Executes the given function [action] specified number of [times].
 *
 * A zero-based index of current iteration is passed as a parameter to the [action] function.
 *
 * If the [times] parameter is negative or equal to zero, the [action] function is not invoked.
 */
inline fun repeat(times: Int, action: (Int) -> Unit) {
    for (index in 0 until times) {
        action(index)
    }
}
