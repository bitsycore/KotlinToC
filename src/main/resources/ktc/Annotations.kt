@file:DocumentationOnly
package ktc

/**
Reference type. Ref<T> maps to T* in C (pointer to single value).
Nullable Ref<T?> maps to T* that can be NULL.

Ref<T> always points to valid memory. Ref<T?> can point to null memory.
Access the referenced value with .refValue; take a reference with .asRef().

Usage:
    fun read(inBuf: Ref<ByteArray>, inLen: Int): Int
    val fData: Ref<Array<Float>>
    fun next(): Ref<Node?>
*/
class Ref<T> {
	var refValue: T
}

/**
Mark a fixed-size stack array.
@Size(N) Array<T> is emitted as `T[N]` in the struct layout and is passed
as a raw pointer — no $len companion is added.
The N argument must be a compile-time integer literal.

Usage:
    data class Header(val fDigest: @Size(32) ByteArray)
    fun getKey(): @Size(16) ByteArray
*/
annotation class Size(val inN: Int)

/**
Mark a top-level object or property as thread-local storage.
The generated C variable is prefixed with the `_Thread_local` specifier (C11).

Usage:
    @Tls
    object ThreadContext { ... }

    @Tls
    var threadId: Int = 0
*/
annotation class Tls

/**
Mark a top-level object as a pure namespace with no object instance representation.
The object struct, singleton instance, and Any vtable are not emitted.
Extension functions and properties on a @Namespace object become free C functions
(no $self parameter). Use this for objects that exist only to group related constants
or functions under a common name.

Usage:
    object SDL3 {
        @Namespace object Event
        @Namespace object Scancode
    }

    // These become plain C constants — no object instance in C:
    inline val SDL3.Event.Quit    get() = c.SDL_EVENT_QUIT
    inline val SDL3.Scancode.Left get() = c.SDL_SCANCODE_LEFT
*/
annotation class Namespace