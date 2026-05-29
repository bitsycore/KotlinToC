package ktc

/*
 * ═══════════════════════════════════════════════════════════════════
 *  Strings — KTC `String` interface and pure-Kotlin extension API.
 * ═══════════════════════════════════════════════════════════════════
 *
 * Memory model recap (see CLAUDE.md "String and Array return safety"):
 *  - `String` is a value struct { const Char* ptr; Int len; } — passed by
 *    value as a 16-byte struct copy, underlying bytes shared. Behaves like
 *    a C++ string_view / Rust &str.
 *  - Operations split into two categories:
 *      VIEW    — substring slices, trim, take/drop. No allocation; the
 *                returned String aliases the receiver's backing buffer.
 *      MUTATE  — lowercase/uppercase/reversed/repeat/replace/padStart/
 *                padEnd. Need a fresh buffer. KTC alloca's it in the
 *                caller's frame via codegen intrinsic (see
 *                CallMethodBuiltins.kt).
 *
 * The `class String` block below is `@DocumentationOnly` — it provides
 * IDE / doc-generator visibility into the intrinsic surface, but emits
 * no C code. The extension functions further down ARE compiled and
 * inlined at call sites.
 */

// ══════════════════════════════════════════════════════════════════
// MARK: String — intrinsic surface (documentation only)
// ══════════════════════════════════════════════════════════════════

/*
 * Immutable UTF-8 string. Maps to C `ktc_String` (a struct with
 * `ptr: const char*` and `len: int32_t`). String literals are
 * null-terminated; `ptr` can be passed directly to C APIs expecting
 * `const char*`.
 */
@DocumentationOnly
class String {

	// ── Properties ────────────────────────────────────────────

	/** The number of bytes (not Unicode code points) in the string. */
	val length: Int get() = error("Transpiler intrinsic")

	/** The index of the last character: `length - 1` (-1 for empty strings). */
	val lastIndex: Int get() = error("Transpiler intrinsic")

	/** Raw `const char*` pointer to the underlying null-terminated UTF-8 data. */
	val ptr: Ref<Char> get() = error("Transpiler intrinsic")

	/** Number of Unicode code points (runes) — distinct from byte `length`. */
	val runeLen: Int get() = error("Transpiler intrinsic")

	// ── Indexing & equality ───────────────────────────────────

	operator fun get(index: Int): Char = error("Transpiler intrinsic")
	operator fun compareTo(other: String): Int = error("Transpiler intrinsic")

	fun toString(): String = error("Transpiler intrinsic")
	fun hashCode(): Int = error("Transpiler intrinsic")

	// ── Concatenation ─────────────────────────────────────────

	/** Concatenates this string with [other]. Returns a fresh buffer allocated
	 *  in the caller's frame via alloca — see CLAUDE.md for lifetime rules. */
	operator fun plus(other: String): String = error("Transpiler intrinsic")

	// ── Slicing (view) ────────────────────────────────────────

	/** Returns the substring from [startIndex] (inclusive) to [endIndex] (exclusive).
	 *  View-only: no allocation, returned String aliases this. */
	fun substring(startIndex: Int, endIndex: Int = length): String = error("Transpiler intrinsic")

	// ── Search ────────────────────────────────────────────────

	fun startsWith(prefix: String): Boolean = error("Transpiler intrinsic")
	fun endsWith(suffix: String): Boolean = error("Transpiler intrinsic")

	fun contains(other: String): Boolean = error("Transpiler intrinsic")
	fun contains(ch: Char): Boolean = error("Transpiler intrinsic")

	fun indexOf(str: String): Int = error("Transpiler intrinsic")
	fun indexOf(ch: Char): Int = error("Transpiler intrinsic")
	fun lastIndexOf(str: String): Int = error("Transpiler intrinsic")
	fun lastIndexOf(ch: Char): Int = error("Transpiler intrinsic")

	// ── Mutate (allocate via alloca) ──────────────────────────

	/** Returns a new string with characters in reverse order. */
	fun reversed(): String = error("Transpiler intrinsic")

	/** ASCII-only case conversion: A–Z → a–z. Non-ASCII bytes pass through. */
	fun lowercase(): String = error("Transpiler intrinsic")

	/** ASCII-only case conversion: a–z → A–Z. Non-ASCII bytes pass through. */
	fun uppercase(): String = error("Transpiler intrinsic")

	/** Returns this string repeated [n] times (capped to ~64KB stack budget). */
	fun repeat(n: Int): String = error("Transpiler intrinsic")

	/** Returns a new string with every [old] character replaced by [new]. */
	fun replace(old: Char, new: Char): String = error("Transpiler intrinsic")

	/** Pads to [targetLength] characters on the left with [padChar] (default ' '). */
	fun padStart(targetLength: Int, padChar: Char = ' '): String = error("Transpiler intrinsic")

	/** Pads to [targetLength] characters on the right with [padChar] (default ' '). */
	fun padEnd(targetLength: Int, padChar: Char = ' '): String = error("Transpiler intrinsic")

	// ── Predicates ────────────────────────────────────────────

	fun isEmpty(): Boolean = error("Transpiler intrinsic")
	fun isNotEmpty(): Boolean = error("Transpiler intrinsic")

	// ── Parsing ───────────────────────────────────────────────

	/** Parses this string as a decimal [Int]. Throws on invalid input. */
	fun toInt(): Int = error("Transpiler intrinsic")

	/** Parses this string as a decimal [Long]. Throws on invalid input. */
	fun toLong(): Long = error("Transpiler intrinsic")

	/** Parses this string as a [Float]. Throws on invalid input. */
	fun toFloat(): Float = error("Transpiler intrinsic")

	/** Parses this string as a [Double]. Throws on invalid input. */
	fun toDouble(): Double = error("Transpiler intrinsic")

	/** Strict parse: returns true for "true", false for "false", throws otherwise. */
	fun toBooleanStrict(): Boolean = error("Transpiler intrinsic")

	/** Strict parse: returns true/false for "true"/"false", null otherwise. */
	fun toBooleanStrictOrNull(): Boolean? = error("Transpiler intrinsic")

	// ── Unicode (UTF-8) ───────────────────────────────────────

	/** Returns the Unicode code point ([Rune]) at the given byte offset. */
	fun runeAt(byteIndex: Int): Rune = error("Transpiler intrinsic")
}

// ══════════════════════════════════════════════════════════════════
// MARK: String — Kotlin-pure extensions (compose intrinsics)
// ══════════════════════════════════════════════════════════════════
//
// These never allocate — every returned String is a substring view of the
// receiver. Safe to return from non-inline functions because the lifetime
// is the receiver's.

// ── Indexed access ────────────────────────────────────────

/** First character. Throws on empty string via the bounds check on [0]. */
inline fun String.first(): Char = this[0]

/** Last character. Throws on empty string via the bounds check. */
inline fun String.last(): Char = this[this.length - 1]

/* Note: firstOrNull / lastOrNull / getOrNull are implemented as codegen
intrinsics (see CallMethodBuiltins.kt) because inline expansion can't yet
type a result variable as a `ktc_Char$Opt` (Optional<Char>). */

/** Returns the character at [index], or [defaultValue] applied to [index] if out of range. */
inline fun String.getOrElse(index: Int, defaultValue: (Int) -> Char): Char =
	if (index < 0 || index >= this.length) defaultValue(index) else this[index]

// ── Slicing (substring view, no copy) ─────────────────────

/** First [n] characters (clamped to this.length). */
inline fun String.take(n: Int): String =
	if (n >= this.length) this else this.substring(0, if (n < 0) 0 else n)

/** Last [n] characters (clamped to this.length). */
inline fun String.takeLast(n: Int): String {
	if (n <= 0) return ""
	val vStart = this.length - n
	return if (vStart <= 0) this else this.substring(vStart, this.length)
}

/** Drops the first [n] characters. */
inline fun String.drop(n: Int): String =
	if (n >= this.length) "" else this.substring(if (n < 0) 0 else n, this.length)

/** Drops the last [n] characters. */
inline fun String.dropLast(n: Int): String {
	if (n >= this.length) return ""
	return this.substring(0, this.length - if (n < 0) 0 else n)
}

// ── Trim (substring view, no copy) ────────────────────────

/** Removes leading and trailing ASCII whitespace. Returns a view of this. */
inline fun String.trim(): String {
	var vStart = 0
	while (vStart < this.length) {
		val c = this[vStart]
		if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break
		vStart++
	}
	var vEnd = this.length
	while (vEnd > vStart) {
		val c = this[vEnd - 1]
		if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break
		vEnd--
	}
	return this.substring(vStart, vEnd)
}

/** Removes leading ASCII whitespace. */
inline fun String.trimStart(): String {
	var vStart = 0
	while (vStart < this.length) {
		val c = this[vStart]
		if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break
		vStart++
	}
	return this.substring(vStart, this.length)
}

/** Removes trailing ASCII whitespace. */
inline fun String.trimEnd(): String {
	var vEnd = this.length
	while (vEnd > 0) {
		val c = this[vEnd - 1]
		if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break
		vEnd--
	}
	return this.substring(0, vEnd)
}

// ── Blank checks ──────────────────────────────────────────

/** True when the string is empty or contains only ASCII whitespace. */
inline fun String.isBlank(): Boolean {
	var i = 0
	while (i < this.length) {
		val c = this[i]
		if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return false
		i++
	}
	return true
}

/** True when the string contains at least one non-whitespace character. */
inline fun String.isNotBlank(): Boolean = !this.isBlank()

// ==================
// MARK: Prefix / suffix / delimiter views
// ==================
// All return a substring VIEW into the receiver's backing buffer (no allocation). Single-overload
// signatures only: findInlineExtFun disambiguates extension overloads by receiver + arg count, not
// arg type, so Char-vs-String variants of the same arity would be ambiguous (see plan.md).

/** This string with [prefix] removed if it starts with it; otherwise unchanged. */
inline fun String.removePrefix(prefix: String): String =
	if (this.startsWith(prefix)) this.substring(prefix.length, this.length) else this

/** This string with [suffix] removed if it ends with it; otherwise unchanged. */
inline fun String.removeSuffix(suffix: String): String =
	if (this.endsWith(suffix)) this.substring(0, this.length - suffix.length) else this

/** The part before the first [delimiter]; the whole string if the delimiter is absent. */
inline fun String.substringBefore(delimiter: Char): String {
	val i = this.indexOf(delimiter)
	return if (i < 0) this else this.substring(0, i)
}

/** The part after the first [delimiter]; the whole string if the delimiter is absent. */
inline fun String.substringAfter(delimiter: Char): String {
	val i = this.indexOf(delimiter)
	return if (i < 0) this else this.substring(i + 1, this.length)
}

/** The part after the last [delimiter]; the whole string if the delimiter is absent. */
inline fun String.substringAfterLast(delimiter: Char): String {
	val i = this.lastIndexOf(delimiter)
	return if (i < 0) this else this.substring(i + 1, this.length)
}

/** The part before the last [delimiter]; the whole string if the delimiter is absent. */
inline fun String.substringBeforeLast(delimiter: Char): String {
	val i = this.lastIndexOf(delimiter)
	return if (i < 0) this else this.substring(0, i)
}
