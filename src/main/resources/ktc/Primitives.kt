@file:DocumentationOnly
package ktc

// ==================
// MARK: Boolean
// ==================

/** Represents a boolean value: true or false. */
class Boolean {
    /** Returns the logical AND of this and [other]. Short-circuits if this is false. */
    infix fun and(other: Boolean): Boolean = error("Transpiler intrinsic")

    /** Returns the logical OR of this and [other]. Short-circuits if this is true. */
    infix fun or(other: Boolean): Boolean = error("Transpiler intrinsic")

    /** Returns the logical NOT of this value. */
    operator fun not(): Boolean = error("Transpiler intrinsic")

    /** Returns "true" or "false". */
    fun toString(): String = error("Transpiler intrinsic")

    /** Returns a hash code for this value. */
    fun hashCode(): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: Char
// ==================

/** Represents a Unicode character (UTF-32 code point mapped to C `char`). */
class Char {
    /** Converts this character to its numeric [Int] code. */
    fun toInt(): Int = error("Transpiler intrinsic")

    /** Converts to [Long]. */
    fun toLong(): Long = error("Transpiler intrinsic")

    /** Converts to [Float]. */
    fun toFloat(): Float = error("Transpiler intrinsic")

    /** Converts to [Double]. */
    fun toDouble(): Double = error("Transpiler intrinsic")

    /** Converts to [Byte]. */
    fun toByte(): Byte = error("Transpiler intrinsic")

    /** Converts to [Short]. */
    fun toShort(): Short = error("Transpiler intrinsic")

    /** Converts to [Char] (identity). */
    fun toChar(): Char = error("Transpiler intrinsic")

    /** Returns a single-character string. */
    fun toString(): String = error("Transpiler intrinsic")

    /** Returns a hash code for this character. */
    fun hashCode(): Int = error("Transpiler intrinsic")

    operator fun compareTo(other: Char): Int = error("Transpiler intrinsic")
    operator fun plus(other: Int): Char = error("Transpiler intrinsic")
    operator fun minus(other: Char): Int = error("Transpiler intrinsic")
    operator fun minus(other: Int): Char = error("Transpiler intrinsic")
}

// ==================
// MARK: Byte
// ==================

/** 8-bit signed integer (-128 to 127). */
class Byte {
    operator fun plus(other: Byte): Int = error("Transpiler intrinsic")
    operator fun plus(other: Short): Int = error("Transpiler intrinsic")
    operator fun plus(other: Int): Int = error("Transpiler intrinsic")
    operator fun plus(other: Long): Long = error("Transpiler intrinsic")
    operator fun plus(other: Float): Float = error("Transpiler intrinsic")
    operator fun plus(other: Double): Double = error("Transpiler intrinsic")

    operator fun minus(other: Byte): Int = error("Transpiler intrinsic")
    operator fun minus(other: Short): Int = error("Transpiler intrinsic")
    operator fun minus(other: Int): Int = error("Transpiler intrinsic")
    operator fun minus(other: Long): Long = error("Transpiler intrinsic")
    operator fun minus(other: Float): Float = error("Transpiler intrinsic")
    operator fun minus(other: Double): Double = error("Transpiler intrinsic")

    operator fun times(other: Byte): Int = error("Transpiler intrinsic")
    operator fun times(other: Short): Int = error("Transpiler intrinsic")
    operator fun times(other: Int): Int = error("Transpiler intrinsic")
    operator fun times(other: Long): Long = error("Transpiler intrinsic")
    operator fun times(other: Float): Float = error("Transpiler intrinsic")
    operator fun times(other: Double): Double = error("Transpiler intrinsic")

    operator fun div(other: Byte): Int = error("Transpiler intrinsic")
    operator fun div(other: Short): Int = error("Transpiler intrinsic")
    operator fun div(other: Int): Int = error("Transpiler intrinsic")
    operator fun div(other: Long): Long = error("Transpiler intrinsic")
    operator fun div(other: Float): Float = error("Transpiler intrinsic")
    operator fun div(other: Double): Double = error("Transpiler intrinsic")

    operator fun rem(other: Byte): Int = error("Transpiler intrinsic")
    operator fun rem(other: Short): Int = error("Transpiler intrinsic")
    operator fun rem(other: Int): Int = error("Transpiler intrinsic")
    operator fun rem(other: Long): Long = error("Transpiler intrinsic")

    operator fun unaryMinus(): Int = error("Transpiler intrinsic")
    operator fun unaryPlus(): Int = error("Transpiler intrinsic")

    /** Bitwise AND. */
    infix fun and(other: Int): Int = error("Transpiler intrinsic")

    /** Bitwise OR. */
    infix fun or(other: Int): Int = error("Transpiler intrinsic")

    /** Bitwise XOR. */
    infix fun xor(other: Int): Int = error("Transpiler intrinsic")

    /** Bitwise inversion. */
    fun inv(): Int = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toFloat(): Float = error("Transpiler intrinsic")
    fun toDouble(): Double = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")
    fun toChar(): Char = error("Transpiler intrinsic")

    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Byte): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Short): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Int): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Long): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Float): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Double): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: Short
// ==================

/** 16-bit signed integer (-32768 to 32767). */
class Short {
    operator fun plus(other: Byte): Int = error("Transpiler intrinsic")
    operator fun plus(other: Short): Int = error("Transpiler intrinsic")
    operator fun plus(other: Int): Int = error("Transpiler intrinsic")
    operator fun plus(other: Long): Long = error("Transpiler intrinsic")
    operator fun plus(other: Float): Float = error("Transpiler intrinsic")
    operator fun plus(other: Double): Double = error("Transpiler intrinsic")

    operator fun minus(other: Byte): Int = error("Transpiler intrinsic")
    operator fun minus(other: Short): Int = error("Transpiler intrinsic")
    operator fun minus(other: Int): Int = error("Transpiler intrinsic")
    operator fun minus(other: Long): Long = error("Transpiler intrinsic")
    operator fun minus(other: Float): Float = error("Transpiler intrinsic")
    operator fun minus(other: Double): Double = error("Transpiler intrinsic")

    operator fun times(other: Byte): Int = error("Transpiler intrinsic")
    operator fun times(other: Short): Int = error("Transpiler intrinsic")
    operator fun times(other: Int): Int = error("Transpiler intrinsic")
    operator fun times(other: Long): Long = error("Transpiler intrinsic")
    operator fun times(other: Float): Float = error("Transpiler intrinsic")
    operator fun times(other: Double): Double = error("Transpiler intrinsic")

    operator fun div(other: Byte): Int = error("Transpiler intrinsic")
    operator fun div(other: Short): Int = error("Transpiler intrinsic")
    operator fun div(other: Int): Int = error("Transpiler intrinsic")
    operator fun div(other: Long): Long = error("Transpiler intrinsic")
    operator fun div(other: Float): Float = error("Transpiler intrinsic")
    operator fun div(other: Double): Double = error("Transpiler intrinsic")

    operator fun rem(other: Byte): Int = error("Transpiler intrinsic")
    operator fun rem(other: Short): Int = error("Transpiler intrinsic")
    operator fun rem(other: Int): Int = error("Transpiler intrinsic")
    operator fun rem(other: Long): Long = error("Transpiler intrinsic")

    operator fun unaryMinus(): Int = error("Transpiler intrinsic")
    operator fun unaryPlus(): Int = error("Transpiler intrinsic")

    infix fun and(other: Int): Int = error("Transpiler intrinsic")
    infix fun or(other: Int): Int = error("Transpiler intrinsic")
    infix fun xor(other: Int): Int = error("Transpiler intrinsic")
    fun inv(): Int = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toFloat(): Float = error("Transpiler intrinsic")
    fun toDouble(): Double = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")
    fun toChar(): Char = error("Transpiler intrinsic")

    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Byte): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Short): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Int): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Long): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Float): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Double): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: Int
// ==================

/** 32-bit signed integer. Maps to C `int32_t`. */
class Int {
    operator fun plus(other: Byte): Int = error("Transpiler intrinsic")
    operator fun plus(other: Short): Int = error("Transpiler intrinsic")
    operator fun plus(other: Int): Int = error("Transpiler intrinsic")
    operator fun plus(other: Long): Long = error("Transpiler intrinsic")
    operator fun plus(other: Float): Float = error("Transpiler intrinsic")
    operator fun plus(other: Double): Double = error("Transpiler intrinsic")

    operator fun minus(other: Byte): Int = error("Transpiler intrinsic")
    operator fun minus(other: Short): Int = error("Transpiler intrinsic")
    operator fun minus(other: Int): Int = error("Transpiler intrinsic")
    operator fun minus(other: Long): Long = error("Transpiler intrinsic")
    operator fun minus(other: Float): Float = error("Transpiler intrinsic")
    operator fun minus(other: Double): Double = error("Transpiler intrinsic")

    operator fun times(other: Byte): Int = error("Transpiler intrinsic")
    operator fun times(other: Short): Int = error("Transpiler intrinsic")
    operator fun times(other: Int): Int = error("Transpiler intrinsic")
    operator fun times(other: Long): Long = error("Transpiler intrinsic")
    operator fun times(other: Float): Float = error("Transpiler intrinsic")
    operator fun times(other: Double): Double = error("Transpiler intrinsic")

    operator fun div(other: Byte): Int = error("Transpiler intrinsic")
    operator fun div(other: Short): Int = error("Transpiler intrinsic")
    operator fun div(other: Int): Int = error("Transpiler intrinsic")
    operator fun div(other: Long): Long = error("Transpiler intrinsic")
    operator fun div(other: Float): Float = error("Transpiler intrinsic")
    operator fun div(other: Double): Double = error("Transpiler intrinsic")

    operator fun rem(other: Byte): Int = error("Transpiler intrinsic")
    operator fun rem(other: Short): Int = error("Transpiler intrinsic")
    operator fun rem(other: Int): Int = error("Transpiler intrinsic")
    operator fun rem(other: Long): Long = error("Transpiler intrinsic")

    operator fun unaryMinus(): Int = error("Transpiler intrinsic")
    operator fun unaryPlus(): Int = error("Transpiler intrinsic")

    /** Left-shift by [bitCount] bits. */
    infix fun shl(bitCount: Int): Int = error("Transpiler intrinsic")

    /** Signed right-shift by [bitCount] bits. */
    infix fun shr(bitCount: Int): Int = error("Transpiler intrinsic")

    /** Unsigned right-shift by [bitCount] bits. */
    infix fun ushr(bitCount: Int): Int = error("Transpiler intrinsic")

    infix fun and(other: Int): Int = error("Transpiler intrinsic")
    infix fun or(other: Int): Int = error("Transpiler intrinsic")
    infix fun xor(other: Int): Int = error("Transpiler intrinsic")

    /** Bitwise inversion (ones' complement). */
    fun inv(): Int = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toFloat(): Float = error("Transpiler intrinsic")
    fun toDouble(): Double = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")
    fun toChar(): Char = error("Transpiler intrinsic")

    /** Returns the decimal string representation. */
    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Byte): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Short): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Int): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Long): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Float): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Double): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: Long
// ==================

/** 64-bit signed integer. Maps to C `int64_t`. */
class Long {
    operator fun plus(other: Byte): Long = error("Transpiler intrinsic")
    operator fun plus(other: Short): Long = error("Transpiler intrinsic")
    operator fun plus(other: Int): Long = error("Transpiler intrinsic")
    operator fun plus(other: Long): Long = error("Transpiler intrinsic")
    operator fun plus(other: Float): Float = error("Transpiler intrinsic")
    operator fun plus(other: Double): Double = error("Transpiler intrinsic")

    operator fun minus(other: Byte): Long = error("Transpiler intrinsic")
    operator fun minus(other: Short): Long = error("Transpiler intrinsic")
    operator fun minus(other: Int): Long = error("Transpiler intrinsic")
    operator fun minus(other: Long): Long = error("Transpiler intrinsic")
    operator fun minus(other: Float): Float = error("Transpiler intrinsic")
    operator fun minus(other: Double): Double = error("Transpiler intrinsic")

    operator fun times(other: Byte): Long = error("Transpiler intrinsic")
    operator fun times(other: Short): Long = error("Transpiler intrinsic")
    operator fun times(other: Int): Long = error("Transpiler intrinsic")
    operator fun times(other: Long): Long = error("Transpiler intrinsic")
    operator fun times(other: Float): Float = error("Transpiler intrinsic")
    operator fun times(other: Double): Double = error("Transpiler intrinsic")

    operator fun div(other: Byte): Long = error("Transpiler intrinsic")
    operator fun div(other: Short): Long = error("Transpiler intrinsic")
    operator fun div(other: Int): Long = error("Transpiler intrinsic")
    operator fun div(other: Long): Long = error("Transpiler intrinsic")
    operator fun div(other: Float): Float = error("Transpiler intrinsic")
    operator fun div(other: Double): Double = error("Transpiler intrinsic")

    operator fun rem(other: Byte): Long = error("Transpiler intrinsic")
    operator fun rem(other: Short): Long = error("Transpiler intrinsic")
    operator fun rem(other: Int): Long = error("Transpiler intrinsic")
    operator fun rem(other: Long): Long = error("Transpiler intrinsic")

    operator fun unaryMinus(): Long = error("Transpiler intrinsic")
    operator fun unaryPlus(): Long = error("Transpiler intrinsic")

    infix fun shl(bitCount: Int): Long = error("Transpiler intrinsic")
    infix fun shr(bitCount: Int): Long = error("Transpiler intrinsic")
    infix fun ushr(bitCount: Int): Long = error("Transpiler intrinsic")
    infix fun and(other: Long): Long = error("Transpiler intrinsic")
    infix fun or(other: Long): Long = error("Transpiler intrinsic")
    infix fun xor(other: Long): Long = error("Transpiler intrinsic")
    fun inv(): Long = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toFloat(): Float = error("Transpiler intrinsic")
    fun toDouble(): Double = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")
    fun toChar(): Char = error("Transpiler intrinsic")

    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Byte): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Short): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Int): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Long): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Float): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Double): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: Float
// ==================

/** 32-bit IEEE 754 floating-point number. Maps to C `float`. */
class Float {
    operator fun plus(other: Byte): Float = error("Transpiler intrinsic")
    operator fun plus(other: Short): Float = error("Transpiler intrinsic")
    operator fun plus(other: Int): Float = error("Transpiler intrinsic")
    operator fun plus(other: Long): Float = error("Transpiler intrinsic")
    operator fun plus(other: Float): Float = error("Transpiler intrinsic")
    operator fun plus(other: Double): Double = error("Transpiler intrinsic")

    operator fun minus(other: Byte): Float = error("Transpiler intrinsic")
    operator fun minus(other: Short): Float = error("Transpiler intrinsic")
    operator fun minus(other: Int): Float = error("Transpiler intrinsic")
    operator fun minus(other: Long): Float = error("Transpiler intrinsic")
    operator fun minus(other: Float): Float = error("Transpiler intrinsic")
    operator fun minus(other: Double): Double = error("Transpiler intrinsic")

    operator fun times(other: Byte): Float = error("Transpiler intrinsic")
    operator fun times(other: Short): Float = error("Transpiler intrinsic")
    operator fun times(other: Int): Float = error("Transpiler intrinsic")
    operator fun times(other: Long): Float = error("Transpiler intrinsic")
    operator fun times(other: Float): Float = error("Transpiler intrinsic")
    operator fun times(other: Double): Double = error("Transpiler intrinsic")

    operator fun div(other: Byte): Float = error("Transpiler intrinsic")
    operator fun div(other: Short): Float = error("Transpiler intrinsic")
    operator fun div(other: Int): Float = error("Transpiler intrinsic")
    operator fun div(other: Long): Float = error("Transpiler intrinsic")
    operator fun div(other: Float): Float = error("Transpiler intrinsic")
    operator fun div(other: Double): Double = error("Transpiler intrinsic")

    operator fun rem(other: Float): Float = error("Transpiler intrinsic")
    operator fun rem(other: Double): Double = error("Transpiler intrinsic")

    operator fun unaryMinus(): Float = error("Transpiler intrinsic")
    operator fun unaryPlus(): Float = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toFloat(): Float = error("Transpiler intrinsic")
    fun toDouble(): Double = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")

    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Byte): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Short): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Int): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Long): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Float): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Double): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: Double
// ==================

/** 64-bit IEEE 754 floating-point number. Maps to C `double`. */
class Double {
    operator fun plus(other: Byte): Double = error("Transpiler intrinsic")
    operator fun plus(other: Short): Double = error("Transpiler intrinsic")
    operator fun plus(other: Int): Double = error("Transpiler intrinsic")
    operator fun plus(other: Long): Double = error("Transpiler intrinsic")
    operator fun plus(other: Float): Double = error("Transpiler intrinsic")
    operator fun plus(other: Double): Double = error("Transpiler intrinsic")

    operator fun minus(other: Byte): Double = error("Transpiler intrinsic")
    operator fun minus(other: Short): Double = error("Transpiler intrinsic")
    operator fun minus(other: Int): Double = error("Transpiler intrinsic")
    operator fun minus(other: Long): Double = error("Transpiler intrinsic")
    operator fun minus(other: Float): Double = error("Transpiler intrinsic")
    operator fun minus(other: Double): Double = error("Transpiler intrinsic")

    operator fun times(other: Byte): Double = error("Transpiler intrinsic")
    operator fun times(other: Short): Double = error("Transpiler intrinsic")
    operator fun times(other: Int): Double = error("Transpiler intrinsic")
    operator fun times(other: Long): Double = error("Transpiler intrinsic")
    operator fun times(other: Float): Double = error("Transpiler intrinsic")
    operator fun times(other: Double): Double = error("Transpiler intrinsic")

    operator fun div(other: Byte): Double = error("Transpiler intrinsic")
    operator fun div(other: Short): Double = error("Transpiler intrinsic")
    operator fun div(other: Int): Double = error("Transpiler intrinsic")
    operator fun div(other: Long): Double = error("Transpiler intrinsic")
    operator fun div(other: Float): Double = error("Transpiler intrinsic")
    operator fun div(other: Double): Double = error("Transpiler intrinsic")

    operator fun rem(other: Float): Double = error("Transpiler intrinsic")
    operator fun rem(other: Double): Double = error("Transpiler intrinsic")

    operator fun unaryMinus(): Double = error("Transpiler intrinsic")
    operator fun unaryPlus(): Double = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toFloat(): Float = error("Transpiler intrinsic")
    fun toDouble(): Double = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")

    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Byte): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Short): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Int): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Long): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Float): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Double): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: UInt
// ==================

/** 32-bit unsigned integer. Maps to C `uint32_t`. */
class UInt {
    operator fun plus(other: UInt): UInt = error("Transpiler intrinsic")
    operator fun plus(other: ULong): ULong = error("Transpiler intrinsic")
    operator fun minus(other: UInt): UInt = error("Transpiler intrinsic")
    operator fun minus(other: ULong): ULong = error("Transpiler intrinsic")
    operator fun times(other: UInt): UInt = error("Transpiler intrinsic")
    operator fun times(other: ULong): ULong = error("Transpiler intrinsic")
    operator fun div(other: UInt): UInt = error("Transpiler intrinsic")
    operator fun div(other: ULong): ULong = error("Transpiler intrinsic")
    operator fun rem(other: UInt): UInt = error("Transpiler intrinsic")
    operator fun rem(other: ULong): ULong = error("Transpiler intrinsic")

    infix fun shl(bitCount: Int): UInt = error("Transpiler intrinsic")
    infix fun shr(bitCount: Int): UInt = error("Transpiler intrinsic")
    infix fun and(other: UInt): UInt = error("Transpiler intrinsic")
    infix fun or(other: UInt): UInt = error("Transpiler intrinsic")
    infix fun xor(other: UInt): UInt = error("Transpiler intrinsic")
    fun inv(): UInt = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toFloat(): Float = error("Transpiler intrinsic")
    fun toDouble(): Double = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")
    fun toUByte(): UByte = error("Transpiler intrinsic")
    fun toUShort(): UShort = error("Transpiler intrinsic")
    fun toUInt(): UInt = error("Transpiler intrinsic")
    fun toULong(): ULong = error("Transpiler intrinsic")

    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: UInt): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: ULong): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: ULong
// ==================

/** 64-bit unsigned integer. Maps to C `uint64_t`. */
class ULong {
    operator fun plus(other: UInt): ULong = error("Transpiler intrinsic")
    operator fun plus(other: ULong): ULong = error("Transpiler intrinsic")
    operator fun minus(other: UInt): ULong = error("Transpiler intrinsic")
    operator fun minus(other: ULong): ULong = error("Transpiler intrinsic")
    operator fun times(other: UInt): ULong = error("Transpiler intrinsic")
    operator fun times(other: ULong): ULong = error("Transpiler intrinsic")
    operator fun div(other: UInt): ULong = error("Transpiler intrinsic")
    operator fun div(other: ULong): ULong = error("Transpiler intrinsic")
    operator fun rem(other: UInt): ULong = error("Transpiler intrinsic")
    operator fun rem(other: ULong): ULong = error("Transpiler intrinsic")

    infix fun shl(bitCount: Int): ULong = error("Transpiler intrinsic")
    infix fun shr(bitCount: Int): ULong = error("Transpiler intrinsic")
    infix fun and(other: ULong): ULong = error("Transpiler intrinsic")
    infix fun or(other: ULong): ULong = error("Transpiler intrinsic")
    infix fun xor(other: ULong): ULong = error("Transpiler intrinsic")
    fun inv(): ULong = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toFloat(): Float = error("Transpiler intrinsic")
    fun toDouble(): Double = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")
    fun toUByte(): UByte = error("Transpiler intrinsic")
    fun toUShort(): UShort = error("Transpiler intrinsic")
    fun toUInt(): UInt = error("Transpiler intrinsic")
    fun toULong(): ULong = error("Transpiler intrinsic")

    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: UInt): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: ULong): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: UByte / UShort
// ==================

/** 8-bit unsigned integer. Maps to C `uint8_t`. */
class UByte {
    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toUInt(): UInt = error("Transpiler intrinsic")
    fun toULong(): ULong = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")
    fun toUByte(): UByte = error("Transpiler intrinsic")
    fun toUShort(): UShort = error("Transpiler intrinsic")
    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: UByte): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: UInt): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: ULong): Int = error("Transpiler intrinsic")
}

/** 16-bit unsigned integer. Maps to C `uint16_t`. */
class UShort {
    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toUInt(): UInt = error("Transpiler intrinsic")
    fun toULong(): ULong = error("Transpiler intrinsic")
    fun toByte(): Byte = error("Transpiler intrinsic")
    fun toShort(): Short = error("Transpiler intrinsic")
    fun toUByte(): UByte = error("Transpiler intrinsic")
    fun toUShort(): UShort = error("Transpiler intrinsic")
    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: UShort): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: UInt): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: ULong): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: Rune
// ==================

/**
 * A Unicode code point (UTF-32). Maps to C `ktc_Rune` (`int32_t`).
 * Obtained via `String.runeAt(byteIndex)`. Arithmetic and bitwise ops work like [Int].
 */
class Rune {
    operator fun plus(other: Rune): Rune = error("Transpiler intrinsic")
    operator fun minus(other: Rune): Rune = error("Transpiler intrinsic")
    operator fun times(other: Rune): Rune = error("Transpiler intrinsic")
    operator fun div(other: Rune): Rune = error("Transpiler intrinsic")
    operator fun rem(other: Rune): Rune = error("Transpiler intrinsic")
    operator fun unaryMinus(): Rune = error("Transpiler intrinsic")

    infix fun and(other: Rune): Rune = error("Transpiler intrinsic")
    infix fun or(other: Rune): Rune = error("Transpiler intrinsic")
    infix fun xor(other: Rune): Rune = error("Transpiler intrinsic")
    infix fun shl(bitCount: Int): Rune = error("Transpiler intrinsic")
    infix fun shr(bitCount: Int): Rune = error("Transpiler intrinsic")
    fun inv(): Rune = error("Transpiler intrinsic")

    fun toInt(): Int = error("Transpiler intrinsic")
    fun toLong(): Long = error("Transpiler intrinsic")
    fun toChar(): Char = error("Transpiler intrinsic")

    /** Returns the decimal code point value as a string. Use string builder for proper Unicode rendering. */
    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: Rune): Int = error("Transpiler intrinsic")
}

// ==================
// MARK: String
// ==================

/**
 * Immutable UTF-8 string. Maps to C `ktc_String` (a struct with `ptr: const char*` and `len: int32_t`).
 * String literals are null-terminated; `ptr` can be passed directly to C APIs expecting `const char*`.
 */
class String {
    /** The number of bytes (not Unicode code points) in the string. */
    val length: Int get() = error("Transpiler intrinsic")

    /** Raw `const char*` pointer to the underlying null-terminated UTF-8 data. */
    val ptr: @Ptr Char get() = error("Transpiler intrinsic")

    /** Concatenates this string with [other]. */
    operator fun plus(other: String): String = error("Transpiler intrinsic")

    /** Returns the substring from [startIndex] (inclusive) to [endIndex] (exclusive). */
    fun substring(startIndex: Int, endIndex: Int = length): String = error("Transpiler intrinsic")

    /** Returns true if this string starts with [prefix]. */
    fun startsWith(prefix: String): Boolean = error("Transpiler intrinsic")

    /** Returns true if this string ends with [suffix]. */
    fun endsWith(suffix: String): Boolean = error("Transpiler intrinsic")

    /** Returns true if this string contains [other] as a substring. */
    fun contains(other: String): Boolean = error("Transpiler intrinsic")

    /** Returns the index of the first occurrence of [str], or -1 if not found. */
    fun indexOf(str: String): Int = error("Transpiler intrinsic")

    /** Returns the index of the first occurrence of [ch], or -1 if not found. */
    fun indexOf(ch: Char): Int = error("Transpiler intrinsic")

    /** Returns the Unicode code point ([Rune]) at the given byte offset. */
    fun runeAt(byteIndex: Int): Rune = error("Transpiler intrinsic")

    /** Returns the number of Unicode code points (runes) in this string. */
    val runeLen: Int get() = error("Transpiler intrinsic")

    /** Returns true if the string has zero bytes. */
    fun isEmpty(): Boolean = error("Transpiler intrinsic")

    /** Returns true if the string has at least one byte. */
    fun isNotEmpty(): Boolean = error("Transpiler intrinsic")

    /** Parses this string as a decimal [Int]. Throws on invalid input. */
    fun toInt(): Int = error("Transpiler intrinsic")

    /** Parses this string as a decimal [Long]. Throws on invalid input. */
    fun toLong(): Long = error("Transpiler intrinsic")

    /** Parses this string as a [Float]. Throws on invalid input. */
    fun toFloat(): Float = error("Transpiler intrinsic")

    /** Parses this string as a [Double]. Throws on invalid input. */
    fun toDouble(): Double = error("Transpiler intrinsic")

    fun toString(): String = error("Transpiler intrinsic")
    fun hashCode(): Int = error("Transpiler intrinsic")
    operator fun compareTo(other: String): Int = error("Transpiler intrinsic")
    operator fun get(index: Int): Char = error("Transpiler intrinsic")
}
