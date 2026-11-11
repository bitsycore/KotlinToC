package com.bitsycore.ktc2

import java.io.File

/** Name of the file that describes a module, in its directory. */
const val kModuleFileName = "module.ktc2.toml"

// ==================
// MARK: Module file
// ==================

/**
 * A module: a directory with a `module.ktc2.toml`.
 *
 * ```toml
 * name         = "demo"                  # also the name of the program built
 * sources      = ["src"]                 # files and directories; default: the module directory
 * dependencies = ["sdl3", "../shared"]   # a module shipped with ktc2, or a path to one
 * compilerOpts = ["-I/opt/sdl/include"]  # for the C compiler
 * linkerOpts   = ["-L/opt/sdl/lib"]      # for the linker
 * ```
 *
 * A module shipped with ktc2 lists its files with `files = [...]`, since resources cannot be
 * enumerated; it is copied to `<out>/modules/<name>` when a program depends on it.
 */
class ModuleFile(val directory: File, inText: String) {
	private val mValues = HashMap<String, List<String>>()

	val name: String get() = mValues["name"]?.firstOrNull() ?: directory.name
	val sources: List<String> get() = mValues["sources"] ?: listOf(".")
	val dependencies: List<String> get() = mValues["dependencies"] ?: emptyList()
	val compilerOpts: List<String> get() = mValues["compilerOpts"] ?: emptyList()
	val linkerOpts: List<String> get() = mValues["linkerOpts"] ?: emptyList()
	val files: List<String> get() = mValues["files"] ?: emptyList()

	init {
		// The small part of TOML a module file needs: `key = "text"` and `key = ["a", "b"]`, with comments.
		val vQuoted = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
		var vPending = ""
		for (vRawLine in inText.lines()) {
			val vLine = (vPending + " " + vRawLine.substringBefore(" #").let { if (it.trimStart().startsWith("#")) "" else it }).trim()
			// A list may run over several lines.
			if (vLine.count { it == '[' } > vLine.count { it == ']' }) {
				vPending = vLine
				continue
			}
			vPending = ""
			val vEquals = vLine.indexOf('=')
			if (vEquals <= 0) continue
			val vKey = vLine.substring(0, vEquals).trim()
			val vValue = vLine.substring(vEquals + 1).trim()
			val vItems = vQuoted.findAll(vValue).map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
			mValues[vKey] = if (vItems.isEmpty() && vValue.isNotEmpty() && !vValue.startsWith("[")) listOf(vValue) else vItems
		}
	}
}

// ==================
// MARK: Project
// ==================

/** What a module and the modules it depends on add up to. */
class Project(val name: String) {
	val sources = LinkedHashSet<File>()
	val compilerOpts = ArrayList<String>()
	val linkerOpts = ArrayList<String>()
}

/**
 * Collects the sources and options of the module in [inDirectory] and of every module it
 * depends on, each once.
 *
 * @param inOutDir where the modules shipped with ktc2 are copied
 * @return the project, or null after reporting what is wrong
 */
fun loadProject(inDirectory: File, inOutDir: File): Project? {
	val vRoot = ModuleFile(inDirectory, File(inDirectory, kModuleFileName).readText())
	val vProject = Project(vRoot.name)
	val vSeen = HashSet<String>()

	fun fail(inMessage: String): Boolean {
		System.err.println("ktc2: error: $inMessage")
		return false
	}

	fun add(inModule: ModuleFile): Boolean {
		if (!vSeen.add(inModule.directory.canonicalPath)) return true
		for (vEntry in inModule.sources) {
			val vFile = File(inModule.directory, vEntry)
			when {
				vFile.isFile -> vProject.sources += vFile
				vFile.isDirectory -> vFile.walkTopDown()
					// Build output and the examples of a module are not part of it.
					.onEnter { it == vFile || (it.name != "build" && it.name != "out" && it.name != "example" && it.name != "examples" && !File(it, kModuleFileName).isFile) }
					.filter { it.isFile && (it.extension == "kt" || it.extension == "def") }
					.sortedBy { it.path }
					.forEach { vProject.sources += it }
				else -> return fail("module '${inModule.name}': no such source '$vEntry'")
			}
		}
		vProject.compilerOpts += inModule.compilerOpts
		vProject.linkerOpts += inModule.linkerOpts
		for (vDependency in inModule.dependencies) {
			val vLocal = File(inModule.directory, vDependency)
			val vModule = if (File(vLocal, kModuleFileName).isFile) {
				ModuleFile(vLocal, File(vLocal, kModuleFileName).readText())
			} else {
				bundledModule(vDependency, inOutDir) ?: return fail("module '${inModule.name}': no module '$vDependency' (neither a directory with a $kModuleFileName nor a module of ktc2)")
			}
			if (!add(vModule)) return false
		}
		return true
	}

	return if (add(vRoot)) vProject else null
}

/** The module [inName] shipped with ktc2, copied to `<inOutDir>/modules/<inName>`; null when there is none. */
private fun bundledModule(inName: String, inOutDir: File): ModuleFile? {
	fun resource(inFile: String): ByteArray? = Project::class.java.getResourceAsStream("/ktc2/modules/$inName/$inFile")?.use { it.readBytes() }
	val vText = resource(kModuleFileName)?.decodeToString() ?: return null
	val vDirectory = File(inOutDir, "modules/$inName")
	vDirectory.mkdirs()
	val vModule = ModuleFile(vDirectory, vText)
	for (vFile in vModule.files + kModuleFileName) {
		val vBytes = resource(vFile) ?: continue
		val vTarget = File(vDirectory, vFile)
		vTarget.parentFile.mkdirs()
		if (!vTarget.isFile || !vTarget.readBytes().contentEquals(vBytes)) vTarget.writeBytes(vBytes)
	}
	return vModule
}

// ==================
// MARK: Building
// ==================

/**
 * Compiles the C written under `<inOutDir>/c` into a program, with the C compiler named by
 * `KTC2_CC` (default `gcc`) and the options the build manifest lists.
 *
 * @param inIsDebug no optimization, debug information, and the leak report at exit
 * @return the program, or null after reporting the failure
 */
fun buildProgram(inOutDir: File, inName: String, inIsDebug: Boolean): File? {
	val vManifest = File(inOutDir, com.bitsycore.ktc2.frontend.kManifestName).readLines()
	fun values(inKey: String): List<String> = vManifest.filter { it.startsWith("$inKey=") }.map { it.substringAfter('=') }
	val vIsWindows = "win" in System.getProperty("os.name").lowercase()
	val vProgram = File(inOutDir, if (vIsWindows) "$inName.exe" else inName)
	val vMode = if (inIsDebug) listOf("-O0", "-g", "-DKTC2_LEAK_CHECK") else listOf("-O2")
	val vThreads = if (values("threads").isNotEmpty()) listOf("-pthread") else emptyList()
	val vCommand = listOf(System.getenv("KTC2_CC") ?: "gcc", "-std=c11") + vMode + vThreads + listOf("-I", ".", "-o", vProgram.absolutePath) +
		values("cflag") + values("c") + "-lm" + values("ldflag")
	val vProcess = ProcessBuilder(vCommand).directory(File(inOutDir, "c")).inheritIO().start()
	if (vProcess.waitFor() != 0) {
		System.err.println("ktc2: error: the C compiler failed: ${vCommand.take(8).joinToString(" ")} …")
		return null
	}
	return vProgram
}
