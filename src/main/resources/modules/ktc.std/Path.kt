package ktc.std

/**
Immutable filesystem path - a thin wrapper over a String, with the Okio-style
helpers users expect: parent, name, extension, isAbsolute, and `/` for joining.

Conventions:
- The internal separator is forward slash `/`. On Windows the underlying libc
  accepts both `\` and `/`, so we normalize to `/` for consistency and parsing.
- Trailing slashes (other than the root `/` itself or `X:/`) are trimmed at
  construction.
- Drive-letter absolute paths like `C:/foo` are recognized as absolute.

Construct with the factory `pathOf(...)` or directly: `Path("src/main.kt")`.
*/
class Path(val s: String) {

	/** True if absolute (POSIX: starts with `/`; Windows: drive-letter form `X:/...`). */
	val isAbsolute: Boolean get() = s.startsWith("/") || (s.length >= 2 && s[1] == ':')

	/** The last path segment - file or directory name. */
	inline val name: String get() = nameOf(s)

	/** Name minus the trailing dot-extension. Leading dot (hidden files) is preserved. */
	inline val nameWithoutExtension: String get() = nameStripExt(name)

	/** File extension (no leading dot), or `""` if none. Leading-dot hidden files have no extension. */
	inline val extension: String get() = nameExt(name)

	/** Parent path, or `null` if this is the root or a single segment with no slash.
	 *  Inline: substring now copies, so the parent's backing must land in the caller's frame. */
	inline val parent: Path? get() = pathParent(s)

	override fun toString(): String = s
	}

/**
Append a relative segment. If [inChild] is absolute, it REPLACES the base
(Okio semantics). Inline so the joined String lives in alloca-backed storage
in the caller's frame - escaping the Path beyond the caller would dangle.
Top-level extension because member `inline fun` on a class isn't expanded
at the call site by the current codegen.
 */
inline fun Path.child(inChild: String): Path = joinPath(this, inChild)

/** Okio-style join: `path / "sub"` ≡ `path.child("sub")`. Inline for the same
   reason `child` is - the joined String's alloca buffer must live in the
   caller's frame. */
inline operator fun Path.div(inChild: String): Path = joinPath(this, inChild)

/** Sugar - construct a Path from a string. */
inline fun pathOf(inS: String): Path = Path(inS)

// ── internal helpers (top-level so they can be inlined cleanly) ────────

inline fun nameOf(inS: String): String =
	if (inS.lastIndexOf('/') < 0) inS else inS.substring(inS.lastIndexOf('/') + 1)

inline fun nameStripExt(inName: String): String {
	val vI = inName.lastIndexOf('.')
	return if (vI <= 0) inName else inName.substring(0, vI)
	}

inline fun nameExt(inName: String): String {
	val vI = inName.lastIndexOf('.')
	return if (vI <= 0) "" else inName.substring(vI + 1)
	}

// Inline: substring now copies into the caller's frame, so the returned Path's backing buffer must
// live there too (a non-inline return would dangle).
inline fun pathParent(inS: String): Path? {
	val vI = inS.lastIndexOf('/')
	if (vI < 0) return null                                                                            // bare name with no separators
	if (vI == 0) return Path("/")                                                                      // direct child of POSIX root
	if (vI == 2 && inS.length >= 3 && inS[1] == ':') return Path(inS.substring(0, 3))                  // child of `C:/`
	return Path(inS.substring(0, vI))
	}

// Inline so the concatenation's alloca buffer lives in the CALLER's frame -
// otherwise the resulting Path.s field would point at a dead buffer.
inline fun joinPath(inBase: Path, inChild: String): Path {
	if (inChild.isEmpty()) return inBase
	val vChild = Path(inChild)
	if (vChild.isAbsolute) return vChild
	val vBaseS = inBase.s
	val vSep = if (vBaseS == "/" || vBaseS.endsWith("/")) "" else "/"
	return Path("$vBaseS$vSep${vChild.s}")
	}
