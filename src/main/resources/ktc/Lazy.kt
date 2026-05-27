@file:DocumentationOnly
package ktc

/**
Lazy property delegation.

`by lazy { initializer }` defers evaluation of a val's initializer until the
first time the property is read. The result is cached so subsequent reads
return the same value without re-running the initializer.

Local lazy vals use a boolean flag + cache variable on the stack.
Top-level lazy vals use ktc_thread_call_once for thread-safe initialization
(equivalent to LazyThreadSafetyMode.SYNCHRONIZED).

Only `val` properties can be lazy — `var` is rejected at parse time.

Usage:
    val config by lazy { loadConfig() }

    val answer: Int by lazy {
        val a = 6
        val b = 7
        a * b
    }

Limitations:
    - Class member `by lazy` is not yet supported — use top-level or local vals.
    - General `by` property delegation is not supported — only `by lazy` is recognized.
*/
