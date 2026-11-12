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
 * name         = "demo"
 * executable   = "demo"                  # the program built; default: the name
 * main         = "app.start"             # the function the program starts with; default: the one `main`
 * sources      = ["src"]                 # files and directories; default: the module directory
 * dependencies = ["sdl3", "../shared",   # a module shipped with ktc2, a path to one,
 *                 "https://github.com/user/repo.git#modules/ui@v1.2"]   # or one in a git repository
 * fetch        = ["SDL3=https://github.com/libsdl-org/SDL.git@release-3.4.8"]   # C sources to have at hand
 * compilerOpts = ["-I${SDL3}/include"]   # for the C compiler; ${NAME} is where a fetched repository is
 * linkerOpts   = ["-L/opt/sdl/lib"]      # for the linker, when the program is built without CMake
 * cmake        = "module.cmake"          # included by the generated CMakeLists.txt
 * ```
 *
 * A git dependency is `url[#directory][@branch or tag]`. It, and what `fetch` names, is cloned
 * once into the cache (`~/.ktc2/cache`, or the directory `KTC2_CACHE` names).
 *
 * A module shipped with ktc2 lists its files with `files = [...]`, since resources cannot be
 * enumerated; it is copied to `<out>/modules/<name>` when a program depends on it.
 */
class ModuleFile(val directory: File, inText: String) {
	private val mValues = HashMap<String, List<String>>()

	val name: String get() = mValues["name"]?.firstOrNull() ?: directory.name
	val executable: String? get() = mValues["executable"]?.firstOrNull()
	val main: String? get() = mValues["main"]?.firstOrNull()
	val sources: List<String> get() = mValues["sources"] ?: listOf(".")
	val dependencies: List<String> get() = mValues["dependencies"] ?: emptyList()
	val fetch: List<String> get() = mValues["fetch"] ?: emptyList()
	val compilerOpts: List<String> get() = mValues["compilerOpts"] ?: emptyList()
	val linkerOpts: List<String> get() = mValues["linkerOpts"] ?: emptyList()
	val cmake: String? get() = mValues["cmake"]?.firstOrNull()
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
// MARK: Git
// ==================

/** A place in a git repository: `url[#directory][@branch or tag]`. */
class GitSource(inText: String) {
	val url: String
	val directory: String
	val revision: String?

	init {
		var vRest = inText
		// A revision comes last, after the last `/`: the `@` of `git@host:…` is not one.
		val vAt = vRest.lastIndexOf('@')
		revision = if (vAt > 0 && vAt > vRest.lastIndexOf('/') && vAt > vRest.lastIndexOf(':')) vRest.substring(vAt + 1).also { vRest = vRest.substring(0, vAt) } else null
		val vHash = vRest.indexOf('#')
		directory = if (vHash >= 0) vRest.substring(vHash + 1).also { vRest = vRest.substring(0, vHash) } else ""
		url = vRest
	}

	/**
	 * The repository at this revision, cloned into the cache the first time it is asked for.
	 *
	 * @return its directory, or null after reporting why it could not be cloned
	 */
	fun checkout(): File? {
		val vCache = System.getenv("KTC2_CACHE")?.let { File(it) } ?: File(System.getProperty("user.home"), ".ktc2/cache")
		val vName = url.removeSuffix(".git").replace(Regex("^[a-z+]+://"), "").replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
		val vTarget = File(vCache, vName + "@" + (revision ?: "default"))
		// A clone that was interrupted has no marker: it is made again.
		val vMarker = File(vTarget, ".ktc2-complete")
		if (vMarker.isFile) return vTarget
		vTarget.deleteRecursively()
		vTarget.parentFile.mkdirs()
		System.err.println("ktc2: fetching $url${revision?.let { " at $it" } ?: ""}")
		val vCommand = listOf("git", "clone", "--depth", "1", "-c", "advice.detachedHead=false") +
			(revision?.let { listOf("--branch", it) } ?: emptyList()) + listOf(url, vTarget.absolutePath)
		val vExit = try {
			ProcessBuilder(vCommand).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
		} catch (vError: java.io.IOException) {
			System.err.println("ktc2: error: git could not be run: ${vError.message}")
			return null
		}
		if (vExit != 0) {
			System.err.println("ktc2: error: could not clone $url${revision?.let { " at $it" } ?: ""} (git exited with $vExit)")
			vTarget.deleteRecursively()
			return null
		}
		vMarker.writeText("")
		return vTarget
	}

	companion object {
		/** True when [inText] names a git repository rather than a directory or a module of ktc2. */
		fun isGit(inText: String): Boolean = "://" in inText || inText.startsWith("git@") || inText.substringBefore('#').substringBefore('@').endsWith(".git")
	}
}

// ==================
// MARK: Project
// ==================

/** What a module and the modules it depends on add up to. */
class Project(val name: String, val executable: String, val main: String?) {
	val sources = LinkedHashSet<File>()
	val compilerOpts = ArrayList<String>()

	/**
	 * Options for the linker: [linkerOpts] when the program is built with CMake, where a module
	 * that has a CMake file does its own linking, [directLinkerOpts] when the C compiler is called.
	 */
	val linkerOpts = ArrayList<String>()
	val directLinkerOpts = ArrayList<String>()

	/** True when a module brings a CMake file: the program is then built with CMake. */
	val needsCMake: Boolean get() = cmakeFiles.isNotEmpty()

	/** The CMake files of the modules, in the order of the dependencies. */
	val cmakeFiles = ArrayList<File>()

	/** The C sources fetched for the modules: the name each is known by, and where it is. */
	val fetched = LinkedHashMap<String, File>()
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
	val vProject = Project(vRoot.name, vRoot.executable ?: vRoot.name, vRoot.main)
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
					// Build output and the examples of a module are not part of it, nor is a module inside it.
					.onEnter { it == vFile || (it.name != "build" && it.name != "out" && it.name != "example" && it.name != "examples" && !File(it, kModuleFileName).isFile) }
					.filter { it.isFile && (it.extension == "kt" || it.extension == "def") }
					.sortedBy { it.path }
					.forEach { vProject.sources += it }
				else -> return fail("module '${inModule.name}': no such source '$vEntry'")
			}
		}
		for (vEntry in inModule.fetch) {
			val vEquals = vEntry.indexOf('=')
			if (vEquals <= 0) return fail("module '${inModule.name}': fetch wants NAME=url, not '$vEntry'")
			val vName = vEntry.substring(0, vEquals).trim()
			val vSource = GitSource(vEntry.substring(vEquals + 1).trim())
			val vCheckout = vSource.checkout() ?: return false
			vProject.fetched[vName] = File(vCheckout, vSource.directory)
		}
		// `${NAME}` in an option is where the repository fetched under that name is.
		fun expanded(inOption: String): String =
			vProject.fetched.entries.fold(inOption) { vText, (vName, vPlace) -> vText.replace("\${$vName}", vPlace.absoluteFile.invariantSeparatorsPath) }
		vProject.compilerOpts += inModule.compilerOpts.map { expanded(it) }
		val vCMake = inModule.cmake?.let { File(inModule.directory, it) }
		if (vCMake != null) {
			if (!vCMake.isFile) return fail("module '${inModule.name}': no such CMake file '${inModule.cmake}'")
			vProject.cmakeFiles += vCMake
		} else {
			vProject.linkerOpts += inModule.linkerOpts.map { expanded(it) }
		}
		vProject.directLinkerOpts += inModule.linkerOpts.map { expanded(it) }
		for (vDependency in inModule.dependencies) {
			val vModule = when {
				GitSource.isGit(vDependency) -> {
					val vSource = GitSource(vDependency)
					val vPlace = File(vSource.checkout() ?: return false, vSource.directory)
					if (!File(vPlace, kModuleFileName).isFile) return fail("module '${inModule.name}': '$vDependency' has no $kModuleFileName")
					ModuleFile(vPlace, File(vPlace, kModuleFileName).readText())
				}
				File(File(inModule.directory, vDependency), kModuleFileName).isFile -> {
					val vLocal = File(inModule.directory, vDependency)
					ModuleFile(vLocal, File(vLocal, kModuleFileName).readText())
				}
				else -> bundledModule(vDependency, inOutDir)
					?: return fail("module '${inModule.name}': no module '$vDependency' (neither a directory with a $kModuleFileName, nor a git repository, nor a module of ktc2)")
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
	for (vFile in vModule.files + kModuleFileName + listOfNotNull(vModule.cmake)) {
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

/** The lines of the build manifest in [inOutDir] that start with `<inKey>=`, without that. */
private fun manifestValues(inOutDir: File, inKey: String): List<String> =
	File(inOutDir, com.bitsycore.ktc2.frontend.kManifestName).readLines().filter { it.startsWith("$inKey=") }.map { it.substringAfter('=') }

private val kIsWindows = "win" in System.getProperty("os.name").lowercase()

/** The C compiler to call: the one `KTC2_CC` names, else the first of gcc, clang and cc there is. */
fun cCompiler(): String {
	System.getenv("KTC2_CC")?.let { return it }
	for (vName in listOf("gcc", "clang", "cc")) {
		if (isOnPath(vName)) return vName
	}
	return "cc"
}

/** Whether a program called [inName] is in one of the directories of `PATH`. */
private fun isOnPath(inName: String): Boolean =
	(System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, inName).isFile || File(it, "$inName.exe").isFile }

/**
 * Writes `<inOutDir>/CMakeLists.txt` for the C that was written under `<inOutDir>/c`: the
 * program, what the bindings ask of the compiler and the linker, and the CMake file of each
 * module, which sees the program as `${KTC2_TARGET}` and each fetched repository as
 * `${KTC2_FETCH_<NAME>}`.
 */
fun writeCMake(inOutDir: File, inExecutable: String, inProject: Project?) {
	fun quoted(inText: String): String = "\"" + inText.replace("\\", "/").replace("\"", "\\\"") + "\""
	val vText = buildString {
		append("# Generated by ktc2. Do not edit: it is written again by each compilation.\n")
		append("cmake_minimum_required(VERSION 3.20)\n")
		append("project(${inExecutable.replace(Regex("[^A-Za-z0-9_]"), "_")} C)\n\n")
		append("set(CMAKE_C_STANDARD 11)\nset(CMAKE_C_STANDARD_REQUIRED ON)\n")
		append("if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)\n\tset(CMAKE_BUILD_TYPE Release)\nendif()\n\n")
		append("add_executable($inExecutable\n")
		manifestValues(inOutDir, "c").forEach { append("\tc/${it.replace('\\', '/')}\n") }
		append(")\n")
		append("target_include_directories($inExecutable PRIVATE c)\n")
		append("# With KTC2_DEBUG the program reports the objects it leaves behind when it ends.\n")
		append("if(KTC2_DEBUG)\n\ttarget_compile_definitions($inExecutable PRIVATE KTC2_LEAK_CHECK)\nendif()\n")
		append("# Kotlin rounds after every floating-point operation: none is fused with the next.\n")
		append("if(NOT MSVC)\n\ttarget_compile_options($inExecutable PRIVATE -ffp-contract=off)\nendif()\n")
		append("# A release build is optimized as one program where the compiler can: -DKTC2_LTO=OFF to do without.\n")
		append("option(KTC2_LTO \"Optimize the release build as a whole program\" ON)\n")
		append("if(KTC2_LTO AND NOT KTC2_DEBUG)\n\tinclude(CheckIPOSupported)\n\tcheck_ipo_supported(RESULT KTC2_HAS_LTO OUTPUT KTC2_LTO_OUTPUT LANGUAGES C)\n")
		append("\tif(KTC2_HAS_LTO)\n\t\tset_property(TARGET $inExecutable PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)\n\tendif()\nendif()\n")
		val vCompilerOpts = manifestValues(inOutDir, "cflag")
		if (vCompilerOpts.isNotEmpty()) append("target_compile_options($inExecutable PRIVATE ${vCompilerOpts.joinToString(" ") { quoted(it) }})\n")
		if (manifestValues(inOutDir, "threads").isNotEmpty()) {
			append("find_package(Threads REQUIRED)\ntarget_link_libraries($inExecutable PRIVATE Threads::Threads)\n")
		}
		append("if(NOT WIN32)\n\ttarget_link_libraries($inExecutable PRIVATE m)\nendif()\n")
		// What the .def files and the modules ask of the linker; a module with a CMake file links there instead.
		val vLinkerOpts = manifestValues(inOutDir, "ldflag")
		if (vLinkerOpts.isNotEmpty()) append("target_link_options($inExecutable PRIVATE ${vLinkerOpts.joinToString(" ") { quoted(it) }})\n")
		if (inProject != null && (inProject.cmakeFiles.isNotEmpty() || inProject.fetched.isNotEmpty())) {
			append("\n# The modules.\nset(KTC2_TARGET $inExecutable)\n")
			for ((vName, vPlace) in inProject.fetched) append("set(KTC2_FETCH_${vName.uppercase()} ${quoted(vPlace.absolutePath)})\n")
			for (vFile in inProject.cmakeFiles) append("include(${quoted(vFile.absolutePath)})\n")
		}
	}
	val vFile = File(inOutDir, "CMakeLists.txt")
	if (!vFile.isFile || vFile.readText() != vText) vFile.writeText(vText)
}

/**
 * Compiles the C written under `<inOutDir>/c` into a program, with the C compiler ([cCompiler])
 * and the options the build manifest lists.
 *
 * @param inIsDebug no optimization, debug information, and the leak report at exit; otherwise optimized, as a whole program where the compiler can
 * @return the program, or null after reporting the failure
 */
fun buildProgram(inOutDir: File, inName: String, inIsDebug: Boolean): File? {
	val vProgram = File(inOutDir, if (kIsWindows) "$inName.exe" else inName)
	val vCompiler = cCompiler()
	// A release build is optimized as one program, so that small functions are inlined across the
	// files of their classes. `KTC2_LTO=0` turns that off, `KTC2_LTO=1` forces it. With clang on
	// Windows it needs LLVM's own linker, so it is used by default only when that one is there.
	val vNeedsLld = kIsWindows && "gcc" !in File(vCompiler).name
	val vWholeProgram = when (System.getenv("KTC2_LTO")) {
		"0" -> false
		"1" -> true
		else -> !vNeedsLld || isOnPath("lld-link")
	}
	// Floating-point operations are never fused (see ktc2_core.h): Kotlin rounds after each one.
	val vMode = listOf("-ffp-contract=off") + when {
		inIsDebug -> listOf("-O0", "-g", "-DKTC2_LEAK_CHECK")
		vWholeProgram -> listOf("-O2", "-flto") + if (vNeedsLld) listOf("-fuse-ld=lld") else emptyList()
		else -> listOf("-O2")
	}
	// On Windows the runtime uses the system's own threads and the math functions are in the C
	// library every program links: naming a thread or a math library there only breaks the
	// compilers that have none (clang with the MSVC libraries).
	val vThreads = if (!kIsWindows && manifestValues(inOutDir, "threads").isNotEmpty()) listOf("-pthread") else emptyList()
	val vMath = if (kIsWindows) emptyList() else listOf("-lm")
	val vCommand = listOf(vCompiler, "-std=c11") + vMode + vThreads + listOf("-I", ".", "-o", vProgram.absolutePath) +
		manifestValues(inOutDir, "cflag") + manifestValues(inOutDir, "c") + vMath + manifestValues(inOutDir, "ldflag")
	val vStatus = try {
		runTool(vCommand, File(inOutDir, "c"))
	} catch (vError: java.io.IOException) {
		System.err.println("ktc2: error: the C compiler could not be run: ${vError.message}")
		return null
	}
	if (vStatus != 0) {
		System.err.println("ktc2: error: the C compiler failed: ${vCommand.take(8).joinToString(" ")} …")
		return null
	}
	return vProgram
}

/**
 * Builds the program with CMake, from the `CMakeLists.txt` [writeCMake] wrote: configured in
 * `<inOutDir>/cmake`, with the arguments `KTC2_CMAKE_ARGS` holds (a generator, a toolchain
 * file…) and the compiler `KTC2_CC` names when it names one.
 *
 * @return the program, or null after reporting the failure
 */
fun buildWithCMake(inOutDir: File, inName: String, inIsDebug: Boolean): File? {
	val vBuildDir = File(inOutDir, "cmake")
	val vExtra = (System.getenv("KTC2_CMAKE_ARGS") ?: "").split(Regex("\\s+(?=-)")).map { it.trim() }.filter { it.isNotEmpty() }
	val vConfiguration = if (inIsDebug) "Debug" else "Release"
	val vConfigure = listOf("cmake", "-S", inOutDir.absolutePath, "-B", vBuildDir.absolutePath, "-DCMAKE_BUILD_TYPE=$vConfiguration") +
		(if (inIsDebug) listOf("-DKTC2_DEBUG=ON") else listOf("-DKTC2_DEBUG=OFF")) +
		(System.getenv("KTC2_CC")?.let { listOf("-DCMAKE_C_COMPILER=$it") } ?: emptyList()) + vExtra
	fun run(inCommand: List<String>): Boolean {
		val vExit = try {
			runTool(inCommand, null)
		} catch (vError: java.io.IOException) {
			System.err.println("ktc2: error: cmake could not be run: ${vError.message}")
			return false
		}
		if (vExit != 0) System.err.println("ktc2: error: cmake failed: ${inCommand.take(6).joinToString(" ")} …")
		return vExit == 0
	}
	if (!run(vConfigure)) return null
	if (!run(listOf("cmake", "--build", vBuildDir.absolutePath, "--config", vConfiguration, "--target", inName))) return null
	// A generator with several configurations puts the program in a directory named after the one built.
	val vFileName = if (kIsWindows) "$inName.exe" else inName
	return listOf(File(vBuildDir, vFileName), File(vBuildDir, "$vConfiguration/$vFileName")).firstOrNull { it.isFile }
		?: run {
			System.err.println("ktc2: error: cmake built no $vFileName in $vBuildDir")
			null
		}
}
