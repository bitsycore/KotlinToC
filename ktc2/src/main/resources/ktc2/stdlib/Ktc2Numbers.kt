@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "ACTUAL_WITHOUT_EXPECT", "UNCHECKED_CAST", "UNUSED_PARAMETER")

package kotlin

import kotlinx.cinterop.internal.cExpr

/*
The bit operations the standard library's Numbers.kt leaves to its platform, over the C
compiler's built-ins.
*/

// ==================
// MARK: Int
// ==================

public actual fun Int.countOneBits(): Int = cExpr("ktc2_popcount32((unsigned)$0)", this)

public actual fun Int.countLeadingZeroBits(): Int = if (this == 0) 32 else cExpr("ktc2_clz32((unsigned)$0)", this)

public actual fun Int.countTrailingZeroBits(): Int = if (this == 0) 32 else cExpr("ktc2_ctz32((unsigned)$0)", this)

public actual fun Int.takeHighestOneBit(): Int = if (this == 0) 0 else 1 shl (31 - countLeadingZeroBits())

public actual fun Int.takeLowestOneBit(): Int = this and -this

public actual fun Int.rotateLeft(bitCount: Int): Int = (this shl bitCount) or (this ushr -bitCount)

public actual fun Int.rotateRight(bitCount: Int): Int = (this shl -bitCount) or (this ushr bitCount)

// ==================
// MARK: Long
// ==================

public actual fun Long.countOneBits(): Int = cExpr("ktc2_popcount64((unsigned long long)$0)", this)

public actual fun Long.countLeadingZeroBits(): Int = if (this == 0L) 64 else cExpr("ktc2_clz64((unsigned long long)$0)", this)

public actual fun Long.countTrailingZeroBits(): Int = if (this == 0L) 64 else cExpr("ktc2_ctz64((unsigned long long)$0)", this)

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

/**
 * A value computed on first use, once, whichever thread asks first: the others wait for it.
 * After that, reading it takes no lock.
 */
private class SynchronizedLazy<T>(initializer: () -> T) : Lazy<T> {
	private var mInitializer: (() -> T)? = initializer
	private var mValue: Any? = null

	override val value: T
		get() {
			if (mInitializer == null) return mValue as T
			return synchronized(this) {
				val vInitializer = mInitializer
				if (vInitializer != null) {
					mValue = vInitializer()
					mInitializer = null
				}
				mValue as T
			}
		}

	override fun isInitialized(): Boolean = mInitializer == null

	override fun toString(): String = if (isInitialized()) value.toString() else "Lazy value not initialized yet."
}

public actual fun <T> lazy(initializer: () -> T): Lazy<T> = SynchronizedLazy(initializer)

public actual fun <T> lazy(mode: LazyThreadSafetyMode, initializer: () -> T): Lazy<T> =
	if (mode == LazyThreadSafetyMode.NONE) UnsafeLazyImpl(initializer) else SynchronizedLazy(initializer)

public actual fun <T> lazy(lock: Any?, initializer: () -> T): Lazy<T> = SynchronizedLazy(initializer)
