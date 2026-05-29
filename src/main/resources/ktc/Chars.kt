package ktc

// ==================
// MARK: Char predicates (ASCII)
// ==================
// Zero-overhead inline extensions with an ASCII fast-path (matching the ASCII-only policy used by
// the String case helpers in Strings.kt). Every tokenizer/parser needs these; without them user
// code is littered with manual `c >= '0' && c <= '9'` range checks.
//
// NOTE: these live in a REAL core file (not Primitives.kt, which is @file:DocumentationOnly and
// whose declarations are intrinsic stubs that codegen does not collect).

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
