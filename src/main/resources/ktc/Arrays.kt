package ktc

// ==================================================
// MARK: Arrays
// ==================================================

/**
Construct a stack array from a fixed list of values.
Usage: val arr = arrayOf(1, 2, 3)
*/
@DocumentationOnly
fun <T> arrayOf(vararg inElements: T): Array<T> = error("Transpiler intrinsic")

/**
Construct a stack array of n elements, all initialised to null.
Usage: val arr = arrayOfNulls<String>(n)
*/
@DocumentationOnly
fun <T> arrayOfNulls(inSize: Int): Array<T?> = error("Transpiler intrinsic")

/**
Construct a stack array of n elements, all zero-initialised.
Usage: val arr = Array<Int>(n)
*/
@DocumentationOnly
fun <T> Array(inSize: Int): Array<T> = error("Transpiler intrinsic")

/**
Construct a stack array of n elements, initialised by calling init(index) for each slot.
Usage: val arr = Array(n) { i -> i * 2 }
*/
@DocumentationOnly
fun <T> Array(inSize: Int, inInit: (Int) -> T): Array<T> = error("Transpiler intrinsic")

// ==================================================
// MARK: Primitive Arrays
// ==================================================

/**
Construct a stack array of n Int elements, all zero-initialised.
Usage: val arr = IntArray(n)
*/
@DocumentationOnly
fun IntArray(inSize: Int): Array<Int> = error("Transpiler intrinsic")

/**
Construct a stack array of n Int elements, initialised by calling init(index).
Usage: val arr = IntArray(n) { i -> i }
*/
@DocumentationOnly
fun IntArray(inSize: Int, inInit: (Int) -> Int): Array<Int> = error("Transpiler intrinsic")

/** Construct a stack array of n Long elements, all zero-initialised. */
@DocumentationOnly
fun LongArray(inSize: Int): Array<Long> = error("Transpiler intrinsic")

/** Construct a stack array of n Long elements, initialised by calling init(index). */
@DocumentationOnly
fun LongArray(inSize: Int, inInit: (Int) -> Long): Array<Long> = error("Transpiler intrinsic")

/** Construct a stack array of n Float elements, all zero-initialised. */
@DocumentationOnly
fun FloatArray(inSize: Int): Array<Float> = error("Transpiler intrinsic")

/** Construct a stack array of n Float elements, initialised by calling init(index). */
@DocumentationOnly
fun FloatArray(inSize: Int, inInit: (Int) -> Float): Array<Float> = error("Transpiler intrinsic")

/** Construct a stack array of n Double elements, all zero-initialised. */
@DocumentationOnly
fun DoubleArray(inSize: Int): Array<Double> = error("Transpiler intrinsic")

/** Construct a stack array of n Double elements, initialised by calling init(index). */
@DocumentationOnly
fun DoubleArray(inSize: Int, inInit: (Int) -> Double): Array<Double> = error("Transpiler intrinsic")

/** Construct a stack array of n Byte elements, all zero-initialised. */
@DocumentationOnly
fun ByteArray(inSize: Int): Array<Byte> = error("Transpiler intrinsic")

/** Construct a stack array of n Byte elements, initialised by calling init(index). */
@DocumentationOnly
fun ByteArray(inSize: Int, inInit: (Int) -> Byte): Array<Byte> = error("Transpiler intrinsic")

/** Construct a stack array of n Char elements, all zero-initialised. */
@DocumentationOnly
fun CharArray(inSize: Int): Array<Char> = error("Transpiler intrinsic")

/** Construct a stack array of n Char elements, initialised by calling init(index). */
@DocumentationOnly
fun CharArray(inSize: Int, inInit: (Int) -> Char): Array<Char> = error("Transpiler intrinsic")

/** Construct a stack array of n Boolean elements, all zero-initialised. */
@DocumentationOnly
fun BooleanArray(inSize: Int): Array<Boolean> = error("Transpiler intrinsic")

/** Construct a stack array of n Boolean elements, initialised by calling init(index). */
@DocumentationOnly
fun BooleanArray(inSize: Int, inInit: (Int) -> Boolean): Array<Boolean> = error("Transpiler intrinsic")

// ==================================================
// MARK: Primitive arrayOf variants
// ==================================================

/** Construct a stack array from a fixed list of Int values. */
@DocumentationOnly
fun intArrayOf(vararg inElements: Int): Array<Int> = error("Transpiler intrinsic")

/** Construct a stack array from a fixed list of Long values. */
@DocumentationOnly
fun longArrayOf(vararg inElements: Long): Array<Long> = error("Transpiler intrinsic")

/** Construct a stack array from a fixed list of Float values. */
@DocumentationOnly
fun floatArrayOf(vararg inElements: Float): Array<Float> = error("Transpiler intrinsic")

/** Construct a stack array from a fixed list of Double values. */
@DocumentationOnly
fun doubleArrayOf(vararg inElements: Double): Array<Double> = error("Transpiler intrinsic")

/** Construct a stack array from a fixed list of Byte values. */
@DocumentationOnly
fun byteArrayOf(vararg inElements: Byte): Array<Byte> = error("Transpiler intrinsic")

/** Construct a stack array from a fixed list of Char values. */
@DocumentationOnly
fun charArrayOf(vararg inElements: Char): Array<Char> = error("Transpiler intrinsic")

/** Construct a stack array from a fixed list of Boolean values. */
@DocumentationOnly
fun booleanArrayOf(vararg inElements: Boolean): Array<Boolean> = error("Transpiler intrinsic")

// ==================================================
// MARK: Array copy / slice
// ==================================================

/**
Returns a copy of this array of the given [inNewSize].
Elements beyond the original length are zero-initialised (expansion).
If inNewSize < size, the array is truncated.
When called with a compile-time literal, the result is treated as @Size(inNewSize)
and can be assigned to a @Size(N) target without a warning — use this instead of
direct assignment from an unsized or larger array.
Usage: val vFixed: @Size(5) IntArray = vDyn.copyOf(5)
*/
@DocumentationOnly
fun <T> Array<T>.copyOf(inNewSize: Int): Array<T> = error("Transpiler intrinsic")

/**
Fills the elements in [inFromIndex, inToIndex) with [inValue] (defaults to the whole array).
Compiles to memset when the element is byte-sized or [inValue] is a zero literal,
otherwise to a bounded element loop.
Usage: vArr.fill(0)  /  vArr.fill(0, 2, 5)
*/
@DocumentationOnly
fun <T> Array<T>.fill(inValue: T, inFromIndex: Int = 0, inToIndex: Int = size): Unit = error("Transpiler intrinsic")

/**
Returns a @Ptr RawArray<T> aliasing this array's data (no copy, no length).
The caller is responsible for tracking the element count.
Usage: val vRaw: @Ptr RawArray<Int> = vArr.asRaw()
*/
@DocumentationOnly
fun <T> Array<T>.asRaw(): @Ptr RawArray<T> = error("Transpiler intrinsic")

// ==================================================
// MARK: RawArray
// ==================================================

/**
A raw C pointer to an array of T elements with no companion $len field.
RawArray<T> is always used together with @Ptr:
@Ptr RawArray<T>  →  T*

Unlike Array<T>, RawArray has no length tracking. The caller is responsible
for keeping track of the element count. Use this only when interfacing with
C APIs that expect a bare pointer or for optimizing class like HashMap implementation.

Heap-allocating a RawArray:
val vRaw: @Ptr RawArray<Byte> = RawArray<Byte>.allocWith(Heap, n)

Getting a raw pointer from a regular stack Array<T>:
val vArr = ByteArray(n)
val vRaw: @Ptr RawArray<Byte> = vArr.ptr()
 */
@DocumentationOnly
class RawArray<T>

/**
Fills the elements in [inFromIndex, inToIndex) with [inValue]. RawArray has no length, so
[inSize] is the element count (and the default toIndex). Defaults fill the whole [0, inSize) range.
Compiles to memset when the element is byte-sized or [inValue] is a zero literal,
otherwise to a bounded element loop.
Usage: vRaw.fill(n, 0)  /  vRaw.fill(n, 0, 2, 5)
*/
@DocumentationOnly
fun <T> RawArray<T>.fill(inSize: Int, inValue: T, inFromIndex: Int = 0, inToIndex: Int = inSize): Unit = error("Transpiler intrinsic")

/**
Returns a @Ptr Array<T> (length-tracked) aliasing this raw array's data with length [inCount].
No copy is made — both views share the same underlying memory.
Usage: val vArr: @Ptr Array<Int> = vRaw.asArray(n)
*/
@DocumentationOnly
fun <T> RawArray<T>.asArray(inCount: Int): @Ptr Array<T> = error("Transpiler intrinsic")

/**
Reallocates this raw array to [inNewCount] elements via the allocator and returns the
(possibly moved) new pointer. Existing contents up to min(old, new) are preserved.
RawArray has no length, so the new count must be supplied explicitly.
Usage: vRaw = vRaw.resizeWith(Heap, n)
*/
@DocumentationOnly
fun <T> RawArray<T>.resizeWith(inAllocator: Allocator, inNewCount: Int): @Ptr RawArray<T> = error("Transpiler intrinsic")
