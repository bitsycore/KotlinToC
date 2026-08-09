@file:DocumentationOnly
package ktc

/** Base class of all Kotlin classes. Every class implicitly extends Any. */
open class Any {
    /** Returns a string representation of this object. */
    open fun toString(): String = error("Transpiler intrinsic")

    /** Returns a hash code value for this object. */
    open fun hashCode(): Int = error("Transpiler intrinsic")

    /** Indicates whether some other object is equal to this one. */
    open fun equals(other: Any?): Boolean = error("Transpiler intrinsic")

    /**
     * Releases resources held by this object.
     * Called automatically when the object goes out of scope if it is heap-allocated.
     * Override to free native resources. Do not call super.dispose() - it is a no-op.
     */
    open fun dispose() = error("Transpiler intrinsic")
}

/**
Heap-allocates the constructed receiver via [allocator] and returns an owning `Ref<T>` to it.
The construction is fused with the allocation - the object is built directly in the allocated
storage (no stack temporary).
Usage: val p = Vec2(1.0f, 2.0f).allocWith(Heap)   /   val a = Array<Int>(n).allocWith(Heap)
*/
fun <T> T.allocWith(allocator: Allocator): Ref<T> = error("Transpiler intrinsic")