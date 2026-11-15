@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kotlinx.io.files

import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import ktc2.kotlinxio.*
import platform.posix.free
import platform.posix.strerror

/*
ktc2's part of kotlinx.io.files: what kotlinx-io gives each system apart (its mingwMain, unixMain,
linuxMain, appleMain), given here once. What differs between Windows and the others is decided by
the C of kotlinxio.h; dirname and basename are written here, after POSIX, with `\` and a drive
letter besides on Windows (MSVC's C library has no libgen.h).
*/

private val kIsWindows: Boolean = kotlinxio_is_windows() != 0

private fun isSeparator(inChar: Char): Boolean = inChar == '/' || (kIsWindows && inChar == '\\')

/** The length of what starts the path and is not a name: "C:" on Windows. */
private fun drivePrefix(inPath: String): Int = if (kIsWindows && inPath.length >= 2 && inPath[1] == ':') 2 else 0

private fun failure(inWhat: String, inCode: Int): IOException = IOException("$inWhat failed: ${strerror(inCode)?.toKString()}")

public actual val SystemTemporaryDirectory: Path
	get() {
		val vText = kotlinxio_temporary_directory() ?: throw IOException("No directory for temporary files")
		try {
			return Path(vText.toKString())
		} finally {
			free(vText)
		}
	}

internal actual fun atomicMoveImpl(source: Path, destination: Path) {
	val vCode = kotlinxio_move(source.path, destination.path)
	if (vCode != 0) throw if (kIsWindows) IOException("Move failed with error code: $vCode") else failure("Move", vCode)
}

internal actual fun mkdirImpl(path: String) {
	val vCode = kotlinxio_mkdir(path)
	if (vCode != 0) throw failure("mkdir", vCode)
}

internal actual fun realpathImpl(path: String): String {
	val vText = kotlinxio_realpath(path) ?: throw IllegalStateException()
	try {
		return vText.toKString()
	} finally {
		free(vText)
	}
}

internal actual val DefaultOpenFlags: Int
	get() = kotlinxio_open_flags()

/** The directory of [path], as POSIX's dirname gives it; "" for a path with no separator, as kotlinx-io has it. */
internal actual fun dirnameImpl(path: String): String {
	if (path.none { isSeparator(it) }) return ""
	val vPrefix = drivePrefix(path)
	var vEnd = path.length
	while (vEnd > vPrefix + 1 && isSeparator(path[vEnd - 1])) vEnd--
	while (vEnd > vPrefix && !isSeparator(path[vEnd - 1])) vEnd--
	if (vEnd == vPrefix) return path.substring(0, vPrefix) + "."
	while (vEnd > vPrefix + 1 && isSeparator(path[vEnd - 1])) vEnd--
	return path.substring(0, vEnd)
}

/** The last name of [path], as POSIX's basename gives it. */
internal actual fun basenameImpl(path: String): String {
	val vPrefix = drivePrefix(path)
	var vEnd = path.length
	while (vEnd > vPrefix + 1 && isSeparator(path[vEnd - 1])) vEnd--
	if (vEnd == vPrefix + 1 && isSeparator(path[vPrefix])) return path.substring(vPrefix, vPrefix + 1)
	var vStart = vEnd
	while (vStart > vPrefix && !isSeparator(path[vStart - 1])) vStart--
	return path.substring(vStart, vEnd)
}

internal actual fun isAbsoluteImpl(path: String): Boolean {
	if (!kIsWindows) return path.startsWith('/')
	if (path.startsWith('/') || path.startsWith('\\')) return true
	return path.length > 2 && path[1] == ':' && isSeparator(path[2])
}
