@file:DocumentationOnly

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
     * Override to free native resources. Do not call super.dispose() — it is a no-op.
     */
    open fun dispose() = error("Transpiler intrinsic")
}

fun Any.Companion.allocWith(allocator: Allocator, vararg args: Any?): @Ptr Any = error("Transpiler intrinsic")