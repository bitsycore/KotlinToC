package ktc

// Each primitive below is an intrinsic the transpiler knows natively, so the class blocks are
// per-declaration @DocumentationOnly: parsed for IDE/doc visibility, never collected or emitted.
// Marking per-class (not @file:DocumentationOnly) lets REAL inline extensions live in this file
// alongside the stubs — see the Char predicates below, and plan.md D1.

// ==================
// MARK: Boolean
// ==================

/** Represents a boolean value: true or false. */
@DocumentationOnly
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
@DocumentationOnly
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
// MARK: Char predicates (ASCII)
// ==================
// Zero-overhead inline extensions with an ASCII fast-path (matching the ASCII-only policy used by
// the String case helpers in Strings.kt). Every tokenizer/parser needs these. These are REAL,
// collected declarations living right next to the @DocumentationOnly Char stub above — see plan.md D1.

/** True if this char is an ASCII decimal digit '0'..'9'. */
inline fun Char.isDigit(): Boolean = this >= '0' && this <= '9'

/** True if this char is an ASCII letter a-z or A-Z. */
inline fun Char.isLetter(): Boolean = (this >= 'a' && this <= 'z') || (this >= 'A' && this <= 'Z')

/** True if this char is an ASCII letter or decimal digit. */
inline fun Char.isLetterOrDigit(): Boolean = this.isLetter() || this.isDigit()

/** True for ASCII whitespace: space, tab, newline, carriage return, vertical tab, form feed. */
inline fun Char.isWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\r' || this.toInt() == 11 || this.toInt() == 12

/** True if this char is an ASCII uppercase letter A-Z. */
inline fun Char.isUpperCase(): Boolean = this >= 'A' && this <= 'Z'

/** True if this char is an ASCII lowercase letter a-z. */
inline fun Char.isLowerCase(): Boolean = this >= 'a' && this <= 'z'

/** ASCII uppercase of this char; non-letters are returned unchanged. */
inline fun Char.uppercaseChar(): Char = if (this >= 'a' && this <= 'z') this - 32 else this

/** ASCII lowercase of this char; non-letters are returned unchanged. */
inline fun Char.lowercaseChar(): Char = if (this >= 'A' && this <= 'Z') this + 32 else this

/** Numeric value of an ASCII decimal digit '0'..'9'. Result is undefined for non-digits. */
inline fun Char.digitToInt(): Int = this.toInt() - '0'.toInt()

// ==================
// MARK: Byte
// ==================

/** 8-bit signed integer (-128 to 127). */
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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
@DocumentationOnly
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

// String is declared in Strings.kt (which also carries the pure-Kotlin
// extension API: take/drop/trim/isBlank/getOrNull/...).
