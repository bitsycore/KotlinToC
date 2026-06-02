@file:DocumentationOnly
package ktc

// ==================================================
// MARK: Array
// ==================================================

/**
A length-tracked array of T elements. In C this is a VarArr struct `{ T* ptr; ktc_Int len; }`,
passed by value. Stack-allocated by default (the factory functions below); heap-allocate via
`Array<T>(n).allocWith(allocator)`.

A bare `Array<T>` field cannot be stored in a class/object — use `@Size(N) Array<T>` (fixed,
becomes `T[N]`) or `Ref<Array<T>>` (the same VarArr, but safe to pass and return).

Element access uses `arr[i]`; `size` returns the element count. Related views:
`asRaw()` → `RawArray<T>` (bare data pointer), and `RawArray<T>.asArray(n)` back to a VarArr.
 */
class Array<T>(val size: Int) {

    constructor(size: Int, init: (Int) -> T) : this(size) {
        for (i in 0 until size) {
            this[i] = init(i)
        }
    }

    operator fun get(index: Int): T
    operator fun set(index: Int, value: T): Unit
    operator fun iterator(): Iterator<T>

}

/** Returns a stack copy resized to [newSize]; grown slots are zero-filled, shrunk ones truncated. */
fun <T> Array<T>.copyOf(newSize: Int): Array<T>

/** Fills [fromIndex, toIndex) with [value] (whole array by default). memset when byte-sized / zero, else loop. */
fun <T> Array<T>.fill(value: T, fromIndex: Int = 0, toIndex: Int = size): Unit

/** Identity view of this array as `Ref<Array<T>>` — same VarArr, but safe to pass and return. */
fun <T> Array<T>.asRef(): Ref<Array<T>>

/** Heap-copies this array's data via [allocator], returning an owning `Ref<Array<T>>`. */
fun <T> Array<T>.copyWith(allocator: Allocator): Ref<Array<T>>

/** Copies elements `[startIndex, endIndex)` of this array into [destination] starting at
    [destinationOffset] — a `memcpy` into existing storage, NO allocation. Returns [destination].
    The destination must already have room for the copied range. */
fun <T> Array<T>.copyInto(destination: Array<T>, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): Array<T>

/** Reallocates this array to [newSize] elements via [allocator]; contents up to min(old, new) are preserved. */
fun <T> Array<T>.resizeWith(allocator: Allocator, newSize: Int): Ref<Array<T>>

/** Bare `RawArray<T>` aliasing this array's data (no length); caller tracks the count. */
fun <T> Array<T>.asRaw(): RawArray<T>

/**
Construct a stack array from a fixed list of values.
Usage: val arr = arrayOf(1, 2, 3)
 */
fun <T> arrayOf(vararg inElements: T): Array<T>

/**
Returns an array of objects of the given type with the given size, initialized with null values.
 */
fun <T> arrayOfNulls(size: Int): Array<T?>

// =========================================
// MARK: Primitive Variant
// =========================================

typealias ByteArray = Array<Byte>
typealias ShortArray = Array<Short>
typealias IntArray = Array<Int>
typealias LongArray = Array<Long>
typealias FloatArray = Array<Float>
typealias DoubleArray = Array<Double>
typealias CharArray = Array<Char>
typealias BooleanArray = Array<Boolean>
typealias UByteArray = Array<UByte>
typealias UShortArray = Array<UShort>
typealias UIntArray = Array<UInt>
typealias ULongArray = Array<ULong>

fun byteArrayOf(vararg inElements: Byte): Array<Byte>
fun shortArrayOf(vararg inElements: Short): Array<Short>
fun intArrayOf(vararg inElements: Int): Array<Int>
fun longArrayOf(vararg inElements: Long): Array<Long>
fun ubyteArrayOf(vararg inElements: UByte): Array<UByte>
fun ushortArrayOf(vararg inElements: UShort): Array<UShort>
fun uintArrayOf(vararg inElements: UInt): Array<UInt>
fun ulongArrayOf(vararg inElements: ULong): Array<ULong>
fun floatArrayOf(vararg inElements: Float): Array<Float>
fun doubleArrayOf(vararg inElements: Double): Array<Double>
fun charArrayOf(vararg inElements: Char): Array<Char>
fun booleanArrayOf(vararg inElements: Boolean): Array<Boolean>

// ==================================================
// MARK: RawArray
// ==================================================

/**
A raw C pointer to an array of T elements with no companion $len field.
RawArray<T> is inherently a reference type (T* in C).

Unlike Array<T>, RawArray has no length tracking. The caller is responsible
for keeping track of the element count. Use this only when interfacing with
C APIs that expect a bare pointer or for optimizing class like HashMap implementation.

Heap-allocating a RawArray:
val vRaw: RawArray<Byte> = RawArray<Byte>(n).allocWith(Heap)

Getting a raw pointer from a regular stack Array<T>:
val vArr = ByteArray(n)
val vRaw: RawArray<Byte> = vArr.asRaw()
 */
class RawArray<T>

fun <T> RawArray<T>.fill(size: Int, value: T, fromIndex: Int = 0, toIndex: Int = size): Unit
fun <T> RawArray<T>.asArray(count: Int): Ref<Array<T>>
fun <T> RawArray<T>.resizeWith(allocator: Allocator, newCount: Int): RawArray<T>

/** Copies elements `[startIndex, endIndex)` of this raw array into [destination] starting at
    [destinationOffset] — a `memcpy`, no allocation. Returns [destination]. RawArray has no length,
    so [endIndex] is REQUIRED (unlike `Array<T>.copyInto`, where it defaults to `size`). */
fun <T> RawArray<T>.copyInto(destination: RawArray<T>, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int): RawArray<T>
