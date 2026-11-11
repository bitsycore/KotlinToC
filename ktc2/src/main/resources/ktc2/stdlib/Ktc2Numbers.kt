@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "ACTUAL_WITHOUT_EXPECT")

package kotlin

import kotlinx.cinterop.internal.cExpr

/*
The bit operations the standard library's Numbers.kt leaves to its platform, over the C
compiler's built-ins.
*/

// ==================
// MARK: Int
// ==================

public actual fun Int.countOneBits(): Int = cExpr("__builtin_popcount((unsigned)$0)", this)

public actual fun Int.countLeadingZeroBits(): Int = if (this == 0) 32 else cExpr("__builtin_clz((unsigned)$0)", this)

public actual fun Int.countTrailingZeroBits(): Int = if (this == 0) 32 else cExpr("__builtin_ctz((unsigned)$0)", this)

public actual fun Int.takeHighestOneBit(): Int = if (this == 0) 0 else 1 shl (31 - countLeadingZeroBits())

public actual fun Int.takeLowestOneBit(): Int = this and -this

public actual fun Int.rotateLeft(bitCount: Int): Int = (this shl bitCount) or (this ushr -bitCount)

public actual fun Int.rotateRight(bitCount: Int): Int = (this shl -bitCount) or (this ushr bitCount)

// ==================
// MARK: Long
// ==================

public actual fun Long.countOneBits(): Int = cExpr("__builtin_popcountll((unsigned long long)$0)", this)

public actual fun Long.countLeadingZeroBits(): Int = if (this == 0L) 64 else cExpr("__builtin_clzll((unsigned long long)$0)", this)

public actual fun Long.countTrailingZeroBits(): Int = if (this == 0L) 64 else cExpr("__builtin_ctzll((unsigned long long)$0)", this)

public actual fun Long.takeHighestOneBit(): Long = if (this == 0L) 0L else 1L shl (63 - countLeadingZeroBits())

public actual fun Long.takeLowestOneBit(): Long = this and -this

public actual fun Long.rotateLeft(bitCount: Int): Long = (this shl bitCount) or (this ushr -bitCount)

public actual fun Long.rotateRight(bitCount: Int): Long = (this shl -bitCount) or (this ushr bitCount)

// ==================
// MARK: Floating point
// ==================

public actual fun Double.isNaN(): Boolean = this != this

public actual fun Float.isNaN(): Boolean = this != this

public actual fun Double.isInfinite(): Boolean = this == Double.POSITIVE_INFINITY || this == Double.NEGATIVE_INFINITY

public actual fun Float.isInfinite(): Boolean = this == Float.POSITIVE_INFINITY || this == Float.NEGATIVE_INFINITY

public actual fun Double.isFinite(): Boolean = !isInfinite() && !isNaN()

public actual fun Float.isFinite(): Boolean = !isInfinite() && !isNaN()

/** The IEEE 754 bits of the value, every NaN giving the same ones. */
public actual fun Double.toBits(): Long = if (this != this) 0x7ff8000000000000L else toRawBits()

public actual fun Double.toRawBits(): Long = cExpr("((union { double value; int64_t bits; }){ .value = $0 }).bits", this)

public actual fun Double.Companion.fromBits(bits: Long): Double = cExpr("((union { int64_t bits; double value; }){ .bits = $0 }).value", bits)

public actual fun Float.toBits(): Int = if (this != this) 0x7fc00000 else toRawBits()

public actual fun Float.toRawBits(): Int = cExpr("((union { float value; int32_t bits; }){ .value = $0 }).bits", this)

public actual fun Float.Companion.fromBits(bits: Int): Float = cExpr("((union { int32_t bits; float value; }){ .bits = $0 }).value", bits)

// ==================
// MARK: Lazy
// ==================

/** A value computed on first use. Not synchronized: a lazy value shared between threads needs its own lock. */
public actual fun <T> lazy(initializer: () -> T): Lazy<T> = UnsafeLazyImpl(initializer)

public actual fun <T> lazy(mode: LazyThreadSafetyMode, initializer: () -> T): Lazy<T> = UnsafeLazyImpl(initializer)

public actual fun <T> lazy(lock: Any?, initializer: () -> T): Lazy<T> = UnsafeLazyImpl(initializer)
