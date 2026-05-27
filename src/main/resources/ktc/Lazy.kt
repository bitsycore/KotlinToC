@file:DocumentationOnly
package ktc

/** Lazy property wrapper. Defers evaluation until first read; caches the result.
KTC lowers `by lazy { ... }` at compile time — no runtime Lazy object is allocated. */
interface Lazy<T> {
	val value: T
	fun isInitialized(): Boolean
}


enum class LazyThreadSafetyMode {
	/** Thread-safe init via ktc_thread_call_once (INIT_ONCE on Windows, pthread_once on POSIX).
	Only one thread executes the initializer; all others see the result. Default for top-level. */
	SYNCHRONIZED,

	/** No synchronization. Used implicitly for local lazy vals (stack-scoped). */
	NONE,
}

/** Creates a lazy val that evaluates [initializer] on first access.
Top-level: SYNCHRONIZED via static ktc_thread_once_t + ktc_thread_call_once.
Local: NONE via bool flag + cache on the stack.

Usage:
    val config by lazy { loadConfig() }
    val answer: Int by lazy { 6 * 7 }
*/
fun <T> lazy(initializer: () -> T): Lazy<T> = error("Transpiler intrinsic")


/** Internal implementation: thread-safe lazy using ktc_thread_call_once.
Top-level lowering emits:
    static ktc_thread_once_t name$once = KTC_THREAD_ONCE_INIT;
    static T name$cache;
    static void name_lazy_init(void) { name$cache = initializer(); }
    void name_$ensure_init(void) { ktc_thread_call_once(&name$once, name_lazy_init); }
Local lowering emits:
    bool name$inited = false; T name$cache;
    if (!name$inited) { name$cache = initializer(); name$inited = true; }
*/
internal class SynchronizedLazyImpl<out T>(private val initializer: () -> T) : Lazy<T> {

	// A separate bool tracks initialization so T? values (initialized to null) work correctly.
	// This mirrors the C lowering which uses `bool name$inited` rather than a null sentinel.
	private var _inited: Boolean = false
	private var _value: T? = null

	override val value: T
		get() {
			// ktc_thread_call_once ensures this runs exactly once
			if (!_inited) {
				_value = initializer()
				_inited = true
			}
			@Suppress("UNCHECKED_CAST")
			return _value as T
		}

	override fun isInitialized(): Boolean = _inited

	override fun toString(): String = if (isInitialized()) value.toString() else "Lazy value not initialized yet."
}

/** Internal implementation: unsynchronized lazy for local vals (no thread safety). */
internal class UnsafeLazyImpl<out T>(private val initializer: () -> T) : Lazy<T> {

	private var _inited: Boolean = false
	private var _value: T? = null

	override val value: T
		get() {
			if (!_inited) {
				_value = initializer()
				_inited = true
			}
			@Suppress("UNCHECKED_CAST")
			return _value as T
		}

	override fun isInitialized(): Boolean = _inited

	override fun toString(): String = if (isInitialized()) value.toString() else "Lazy value not initialized yet."
}
