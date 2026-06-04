package ktc

/*
 * ═══════════════════════════════════════════════════════════════════
 *  Strings — KTC `String` interface and pure-Kotlin extension API.
 * ═══════════════════════════════════════════════════════════════════
 *
 * Memory model recap (see CLAUDE.md "String and Array return safety"):
 *  - `String` is an OWNED, NUL-terminated value struct { const Char* ptr; Int len; }. Passing one
 *    copies the 16-byte struct (ptr+len, backing shared) — like an Array; use `.copy()` for an
 *    independent duplicate, `.asRef()` / `.copyWith(alloc)` for the Ref<String> form.
 *  - String-producing ops all COPY into a fresh buffer (alloca'd in the caller's frame via a codegen
 *    intrinsic, see CallMethodBuiltins.kt): substring + the slice/trim/prefix extensions
 *    (take/drop/trim/removePrefix/substringBefore…), and lowercase/uppercase/reversed/repeat/replace/
 *    padStart/padEnd. The extensions are `inline` so the copy lands in the caller; returning one from a
 *    non-inline function dangles (E020) — return `Ref<String>` or mark the function `inline`.
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

	/** Raw `const char*` pointer to the underlying NUL-terminated UTF-8 data, for C interop —
	 *  a bare, length-less `RawArray<Char>`. (Replaces the old `.ptr`, no longer allowed — see E055.) */
	val cPtr: RawArray<Char> get() = error("Transpiler intrinsic")

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

	// ── Ownership (String is a read-only Array) ───────────────
	// copy() duplicates into the caller's frame; asRef() / copyWith() / allocWith()
	// give the Ref<String> form. Ref<String> is a ktc_String* (pointer to a heap block
	// holding the header + bytes), released in one freeMem(ref). It is a real pointer
	// rather than a value struct because RawArray<String> and Ref<String> share the
	// Ptr(String) type, so the value form would collide with RawArray<String>.

	/** Explicit pass-by-value: a fresh, independent, NUL-terminated copy owned in the caller's frame
	 *  (no shared backing with the source). A plain String pass shares the backing bytes — cheap, and
	 *  fine for an immutable view; use copy() when you need an independent owned value. Mirrors Array.copyOf. */
	fun copy(): String = error("Transpiler intrinsic")

	/** A Ref<String> (&this) aliasing this string. Frame-bound — must not outlive the receiver. */
	fun asRef(): Ref<String> = error("Transpiler intrinsic")

	/** Heap-copies the bytes (+ NUL) via [allocator] into one block → a Ref<String> that
	 *  escapes the frame. Release with allocator.freeMem(ref). */
	fun copyWith(allocator: Allocator): Ref<String> = error("Transpiler intrinsic")

	/** Move-to-heap alias of [copyWith] for an existing String value. */
	fun allocWith(allocator: Allocator): Ref<String> = error("Transpiler intrinsic")

	// ── Slicing (copies) ──────────────────────────────────────

	/** Returns the substring from [startIndex] (inclusive) to [endIndex] (exclusive).
	 *  COPIES into a fresh NUL-terminated buffer in the caller's frame (an owned String). */
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
// Every returned String is an owned, NUL-terminated COPY (substring copies now). These are `inline`,
// so the copy lands in the caller's frame; returning one from a non-inline function dangles (E020).

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

// ── Slicing (substring copy) ──────────────────────────────

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

// ── Trim (substring copy) ─────────────────────────────────

/** Removes leading and trailing ASCII whitespace. Returns an owned copy. */
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
// MARK: Prefix / suffix / delimiter (copies)
// ==================
// All COPY a substring into the caller's frame (substring copies now; inline so the copy lands in
// the caller). Single-overload
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
