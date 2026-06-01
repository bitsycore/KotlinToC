package ktc.std

// ==================
// MARK: FileMetadata
// ==================

/**
File metadata snapshot returned by `FileSystem.metadata(path)`.
Mirrors Okio's `FileMetadata` minus the FileSystem-specific extras
(symlink target, creation time on Windows, etc.) that don't have
a uniformly cross-platform answer through libc stat().
 */
/* isRegularFile/isDirectory: at most one is true for a stat'd entry.
   size: file size in bytes (0 for directories).
   lastModifiedMs: mtime as milliseconds since epoch. */
data class FileMetadata(
	val isRegularFile:  Boolean,
	val isDirectory:    Boolean,
	val size:           Long,
	val lastModifiedMs: Long
	)

// ==================
// MARK: FileSource
// ==================

/**
Byte-source backed by a libc FILE*. Read N bytes at a time via `read()`,
close with `close()`. Mirrors Okio's Source — minimal surface, no buffer.
A returned count of `0` means EOF; `-1` signals a read error.

Always close via `close()` (or use within a `try { } finally`). Re-reading
after close is undefined behavior — the handle is freed.
 */
class FileSource(private var fp: AnyPtr) {

	/**
	Read up to [byteCount] bytes into [buf] starting at offset 0.
	Returns the number of bytes actually read (`0` at EOF, `-1` on error).
	[buf] is any pointer to writable bytes (RawArray<Byte>, asRaw() of an Array, etc).
	 */
	fun read(buf: AnyPtr, byteCount: Int): Int {
		if (fp == C.NULL) return -1
		return C.ktc_core_fs_fread(fp, buf, byteCount)
		}

	/** Closes the underlying FILE*. Safe to call multiple times. */
	fun close() {
		if (fp != C.NULL) {
			C.ktc_core_fs_fclose(fp)
			fp = C.NULL
			}
		}

	/** True if `close()` has not yet been called. */
	val isOpen: Boolean get() = fp != C.NULL
	}

// ==================
// MARK: FileSink
// ==================

/**
Byte-sink backed by a libc FILE*. Write N bytes via `write()`, flush with
`flush()`, close with `close()`. Mirrors Okio's Sink.
 */
class FileSink(private var fp: AnyPtr) {

	/**
	Write [byteCount] bytes from [buf] starting at offset 0.
	Returns the number of bytes actually written (short writes are possible
	on disk-full conditions).
	[buf] is any pointer to readable bytes (RawArray<Byte>, String.ptr, etc).
	 */
	fun write(buf: AnyPtr, byteCount: Int): Int {
		if (fp == C.NULL) return 0
		return C.ktc_core_fs_fwrite(fp, buf, byteCount)
		}

	/** Flushes the libc stream — durability still depends on the OS page cache. */
	fun flush() {
		if (fp != C.NULL) C.ktc_core_fs_fflush(fp)
		}

	/** Closes the stream (implicitly flushes). Safe to call multiple times. */
	fun close() {
		if (fp != C.NULL) {
			C.ktc_core_fs_fclose(fp)
			fp = C.NULL
			}
		}

	val isOpen: Boolean get() = fp != C.NULL
	}

// ==================
// MARK: FileSystem
// ==================

/**
Cross-platform filesystem operations modeled after Okio's `FileSystem.SYSTEM`.
Backed directly by libc (stat/remove/rename/mkdir, dirent on POSIX,
FindFirstFile on Windows). No background threads, no async — every call is
synchronous and blocks the caller.

Path separators are normalized to `/` inside `Path`; the OS layer accepts
both forms on Windows so no extra translation is needed at the call site.
 */
@Namespace
object FileSystem {

	// ── Queries ────────────────────────────────────────────────

	/** True if [path] exists (file OR directory). */
	fun exists(path: Ref<Path>): Boolean {
		return C.ktc_core_fs_exists(path.s.ptr, path.s.length) != 0
		}

	/**
	Returns metadata for [path]. If the path doesn't exist, returns a sentinel
	with `isRegularFile = false && isDirectory = false && size = -1`.
	Check `.exists` (or `isRegularFile || isDirectory`) before trusting the
	other fields.
	 */
	fun metadata(path: Ref<Path>): FileMetadata {
		var vMeta: C.ktc_core_fs_meta = C.zeroed()
		if (C.ktc_core_fs_metadata(path.s.ptr, path.s.length, C.addr(vMeta)) == 0) {
			return FileMetadata(false, false, -1L, 0L)
			}
		return FileMetadata(
			isRegularFile  = vMeta.is_regular_file != 0,
			isDirectory    = vMeta.is_directory != 0,
			size           = vMeta.size.toLong(),
			lastModifiedMs = vMeta.last_modified_ms.toLong()
			)
		}

	// ── Mutation ───────────────────────────────────────────────

	/** Delete [path]. Returns true on success. Empty directories are accepted; non-empty are rejected. */
	fun delete(path: Ref<Path>): Boolean {
		return C.ktc_core_fs_delete(path.s.ptr, path.s.length) != 0
		}

	/** Rename or move [from] to [to]. Returns true on success. */
	fun rename(from: Ref<Path>, to: Ref<Path>): Boolean {
		return C.ktc_core_fs_rename(from.s.ptr, from.s.length, to.s.ptr, to.s.length) != 0
		}

	/** Create a single directory at [path] (parent must exist). Returns true on success. */
	fun createDirectory(path: Ref<Path>): Boolean {
		return C.ktc_core_fs_mkdir(path.s.ptr, path.s.length) != 0
		}

	// ── Open ───────────────────────────────────────────────────

	/**
	Open [path] for reading. The returned FileSource is always valid as a
	struct; check `.isOpen` to know whether the underlying file was opened
	successfully.
	 */
	fun source(path: Ref<Path>): FileSource {
		val vMode = "rb"
		val vFp: AnyPtr = C.ktc_core_fs_fopen(path.s.ptr, path.s.length, vMode.ptr)
		return FileSource(vFp)
		}

	/**
	Open [path] for writing. Existing content is truncated unless [append] is true.
	The returned FileSink is always valid as a struct; check `.isOpen` to know
	whether the file was opened successfully.
	 */
	fun sink(path: Ref<Path>, append: Boolean = false): FileSink {
		val vMode = if (append) "ab" else "wb"
		val vFp: AnyPtr = C.ktc_core_fs_fopen(path.s.ptr, path.s.length, vMode.ptr)
		return FileSink(vFp)
		}

	// ── Whole-file convenience (Okio-style) ───────────────────

	/**
	Write [byteCount] bytes from [buf] to [path], truncating any existing file.
	Returns true if all bytes were written.
	 */
	fun writeBytes(path: Ref<Path>, buf: AnyPtr, byteCount: Int): Boolean {
		val vSink = sink(path)
		if (!vSink.isOpen) return false
		val vN = vSink.write(buf, byteCount)
		vSink.close()
		return vN == byteCount
		}

	/** Write the bytes of [content] (UTF-8 from the String view) to [path]. */
	fun writeUtf8(path: Ref<Path>, content: String): Boolean {
		val vSink = sink(path)
		if (!vSink.isOpen) return false
		val vN = vSink.write(content.ptr, content.length)
		vSink.close()
		return vN == content.length
		}
	}

// ==================
// MARK: Top-level helpers
// ==================

/**
Enumerate entries of directory [inPath] (no `.` / `..`), invoking [inBlock]
for each filename. Iterator-style: streams entries without materializing an
array, so it's safe on huge directories.
Returns true if the directory was opened successfully.

Inline at the call site — the lambda is expanded directly into the caller.
Member-of-Namespace-object inline doesn't expand cleanly, so this lives at
top-level under the `ktc.std` package.
*/
inline fun listDir(inPath: Path, inBlock: (String) -> Unit): Boolean {
	val vHandle: AnyPtr = C.ktc_core_fs_listdir_open(inPath.s.ptr, inPath.s.length)
	if (vHandle == C.NULL) return false
	val vBuf = CharArray(1024)
	while (true) {
		val vN: Int = C.ktc_core_fs_listdir_next(vHandle, vBuf.asRaw(), 1024)
		if (vN <= 0) break
		// Wrap the entry name as a String view into the stack buffer.
		// The view is alive for the duration of the callback (vBuf is on
		// the stack and outlives the inBlock invocation).
		inBlock(C.ktc_core_string_wrap(vBuf.asRaw(), vN))
		}
	C.ktc_core_fs_listdir_close(vHandle)
	return true
	}
