package com.bitsycore.ktc

import java.io.File

// ==================
// MARK: OutputTracker
// ==================

/*
Records every file the transpiler intends to emit into outDir, writes only
when the content actually changes (preserving mtime so the C compiler can
skip unchanged translation units), and at the end of the run deletes any
transpiler-managed file in outDir that wasn't part of this emission set.

Managed file types are .c, .h, CMakeLists.txt, and ktc_modules.cmake.
User-provided files (ktc_user.cmake, *.dll, *.exe, *.so, *.dylib, *.obj,
*.o) and the _cmake/ build cache are left untouched.
*/
internal class OutputTracker(
	private val fRootDir: File
	)
	{
	// Canonical paths of every file write() was asked to manage this run.
	private val fEmitted: MutableSet<String> = mutableSetOf()

	/* Write inContent to inFile only if it differs from what's already on
	disk. Always records the file as part of the emission set so the
	cleanup pass won't remove it. Returns true when the file was actually
	(re)written, false when it was already up-to-date. */
	fun write(
		inFile:    File,
		inContent: String
		): Boolean
		{
		fEmitted += inFile.canonicalPath
		if (inFile.exists() && inFile.readText(Charsets.UTF_8) == inContent) return false
		inFile.parentFile?.mkdirs()
		inFile.writeText(inContent)
		return true
		}

	/* Walks fRootDir and removes every transpiler-managed file that wasn't
	emitted this run. Empty package directories left behind are also
	pruned so the tree stays clean. */
	fun cleanupStale(): List<String>
		{
		val vRemoved = mutableListOf<String>()
		fRootDir.walkTopDown().forEach { vF ->
			if (!vF.isFile) return@forEach
			if (!isManaged(vF)) return@forEach
			if (vF.canonicalPath in fEmitted) return@forEach
			vRemoved += vF.path
			vF.delete()
			}
		// Prune empty dirs (bottom-up so children are gone first). Skip the
		// root and _cmake/ - _cmake is the build cache, not ours to clean.
		fRootDir.walkBottomUp().forEach { vD ->
			if (!vD.isDirectory)                                          return@forEach
			if (vD.canonicalPath == fRootDir.canonicalPath)               return@forEach
			if (vD.name == "_cmake" || vD.path.contains("${File.separator}_cmake")) return@forEach
			if (vD.listFiles()?.isEmpty() == true) vD.delete()
			}
		return vRemoved
		}

	/* True for files the transpiler owns: generated C source, generated
	headers, the CMakeLists, and the optional modules cmake snippet. */
	private fun isManaged(inFile: File): Boolean
		{
		val vName = inFile.name
		val vPath = inFile.path
		// _cmake/ is the cmake build cache - never ours.
		if (vPath.contains("${File.separator}_cmake${File.separator}")) return false
		// User-supplied cmake override and any binary artifacts: leave alone.
		if (vName == "ktc_user.cmake")                                   return false
		val vLower = vName.lowercase()
		if (vLower.endsWith(".dll") || vLower.endsWith(".so") || vLower.endsWith(".dylib")) return false
		if (vLower.endsWith(".exe") || vLower.endsWith(".obj") || vLower.endsWith(".o"))    return false
		return vName.endsWith(".c") || vName.endsWith(".h") ||
		       vName == "CMakeLists.txt" || vName == "ktc_modules.cmake"
		}
	}
