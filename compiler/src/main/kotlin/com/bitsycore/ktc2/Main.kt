package com.bitsycore.ktc2

import com.bitsycore.ktc2.frontend.K2Frontend
import java.io.File
import kotlin.system.exitProcess

private const val kUsage = """Usage: ktc2 <module directory | file.kt | library.def>... [options] [-- program arguments]
  -o <dir>           where the output goes (default: <module>/build, or ./out)
  --build            also compile the C into a program (C compiler: KTC2_CC, else gcc, clang or cc)
  --cmake            build with CMake, from the CMakeLists.txt written next to the C
                     (always so when a module has a CMake file; KTC2_CMAKE_ARGS: more arguments)
  --run              build, then run the program with the arguments after --
  --debug            build without optimization, with debug information and the leak report
  -copt <option>     an option for the C compiler; -lopt <option> one for the linker
  -ea                assert() checks (-enable-assertions, as Kotlin/Native's; off otherwise, --debug included)
  --checks=abort     an index out of bounds or a division by zero ends the program instead of throwing
  --warn-heap        list every allocation left on the heap, with the reason
  --no-warn-cycles   do not report classes that can form reference cycles
  --dump-ir          also write the IR received from the Kotlin compiler, and ktc2's own after each pass (kir/)
  -q, --quiet        no line for each step (fetching, translating, compiling); KTC2_QUIET=1 the same
  --stop-daemon      end the compiler process that ktc2 keeps running between commands (KTC2_DAEMON=0: none is used)"""

/** Ends the command being run, with the status it exits with. */
private class Exit(val status: Int) : RuntimeException()

/** The options a command leaves in system properties: forgotten before the next one. */
private val kCommandProperties = listOf("ktc2.warnHeap", "ktc2.checks", "ktc2.warnCycles", "ktc2.copt", "ktc2.lopt", "ktc2.main", "ktc2.weak", "ktc2.acyclic", "ktc2.moduleOptIn")

/**
 * Entry point of the ktc2 transpiler (Kotlin semantics on ARC - see DESIGN.md).
 * Writes the generated C under `<out>/c` and a `CMakeLists.txt` for it; with `--build`,
 * `--cmake` or `--run` also the program.
 *
 * The work is done by a process that stays between commands when there is one (`Daemon.kt`),
 * by this one otherwise.
 */
fun main(args: Array<String>) {
	if (args.firstOrNull() == kServeDaemon) {
		serveDaemon(args.getOrElse(1) { "" }) { vArguments, vDirectory -> runCommand(vArguments, vDirectory) }
		exitProcess(0)
	}
	// The process that runs the command, here or the one that stays, is told whether it may rewrite a line in place.
	val vInTerminal = System.console() != null && System.getenv("TERM") != "dumb" && args.firstOrNull() != kStopDaemon
	val vArguments = if (vInTerminal) arrayOf(kTerminalArgument) + args else args
	runThroughDaemon(vArguments)?.let { exitProcess(it) }
	val (vStatus, vProgram) = runCommand(vArguments, null)
	if (vStatus != 0 || vProgram == null) exitProcess(vStatus)
	exitProcess(ProcessBuilder(vProgram).inheritIO().start().waitFor())
}

/**
 * Does what the command [args] asks.
 *
 * @param inDirectory the directory the command was given in, which its paths are relative to; null for the one of this process
 * @return the status to exit with and, for `--run` when all went well, the program to start with its arguments
 */
fun runCommand(args: Array<String>, inDirectory: File?): Pair<Int, List<String>?> {
	kCommandProperties.forEach { System.clearProperty(it) }
	Progress.isTerminal = false
	Progress.isShown = System.getenv("KTC2_QUIET") != "1"
	return try {
		0 to command(args, inDirectory)
	} catch (vExit: Exit) {
		vExit.status to null
	}
}

private fun command(args: Array<String>, inDirectory: File?): List<String>? {
	// Without `.` or `..` in it: a path the Kotlin compiler reports a file by is the same as the one given (`ktc2 .`).
	fun path(inText: String): File = File(inText).let { if (inDirectory == null || it.isAbsolute) it else File(inDirectory, inText) }.absoluteFile.normalize()
	val vSources = LinkedHashSet<File>()
	val vModules = ArrayList<File>()
	var vOutDir: File? = null
	var vDumpIr = false
	var vBuild = false
	var vRun = false
	var vDebug = false
	var vCMake = false
	var vProgramArguments = emptyList<String>()
	var vIndex = 0
	while (vIndex < args.size) {
		val vArg = args[vIndex++]
		when {
			vArg == "--" -> {
				vProgramArguments = args.drop(vIndex)
				vIndex = args.size
			}
			vArg == "-o" && vIndex < args.size -> vOutDir = path(args[vIndex++])
			vArg == kTerminalArgument -> Progress.isTerminal = true
			vArg == "--quiet" || vArg == "-q" -> Progress.isShown = false
			vArg == "--build" -> vBuild = true
			vArg == "--cmake" -> vCMake = true
			vArg == "--run" -> vRun = true
			vArg == "--debug" -> vDebug = true
			vArg == "--dump-ir" -> vDumpIr = true
			vArg == "--warn-heap" -> System.setProperty("ktc2.warnHeap", "true")
			vArg == "--checks=abort" -> System.setProperty("ktc2.checks", "abort")
			vArg == "--checks=throw" -> System.setProperty("ktc2.checks", "throw")
			vArg == "--no-warn-cycles" -> System.setProperty("ktc2.warnCycles", "false")
			vArg == "-copt" && vIndex < args.size -> addOption("ktc2.copt", args[vIndex++])
			// As Kotlin/Native's compiler: assertions are checked when asked for, in any build.
			vArg == "-ea" || vArg == "-enable-assertions" || vArg == "--enable-assertions" -> addOption("ktc2.copt", "-DKTC2_ASSERTIONS")
			vArg == "-lopt" && vIndex < args.size -> addOption("ktc2.lopt", args[vIndex++])
			vArg.endsWith(".kt") || vArg.endsWith(".def") -> vSources += path(vArg)
			File(path(vArg), kModuleFileName).isFile -> vModules += path(vArg)
			// A directory without a module file stands for the sources and definitions directly in it.
			path(vArg).isDirectory -> vSources += path(vArg).listFiles { vFile -> vFile.isFile && (vFile.extension == "kt" || vFile.extension == "def") }!!.sortedBy { it.name }
			else -> fail("unknown argument '$vArg'")
		}
	}

	val vOut = vOutDir ?: vModules.firstOrNull()?.let { File(it, "build") } ?: path("out")
	var vName = vSources.firstOrNull { it.extension == "kt" }?.nameWithoutExtension ?: "program"
	val vProjects = vModules.map { loadProject(it, vOut) ?: throw Exit(1) }
	// The first module is the program: its name, and where it starts.
	val vProject = vProjects.firstOrNull()
	if (vProject != null) {
		vName = vProject.executable
		vProject.main?.let { System.setProperty("ktc2.main", it) }
	}
	// What the program is compiled with: the standard library and the modules, each at its version.
	val vModulesUsed = listOf("stdlib ${org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION}") + vProjects.flatMap { it.modules }.distinct()
	Progress.note("modules", vModulesUsed.joinToString(", "))
	val vUsesCMake = vCMake || vProjects.any { it.needsCMake }
	for (vEach in vProjects) {
		vSources += vEach.sources
		vEach.compilerOpts.forEach { addOption("ktc2.copt", it) }
		vEach.weak.forEach { addOption("ktc2.weak", it) }
		vEach.acyclic.forEach { addOption("ktc2.acyclic", it) }
		vEach.optIn.forEach { addOption("ktc2.moduleOptIn", it) }
		(if (vUsesCMake) vEach.linkerOpts else vEach.directLinkerOpts).forEach { addOption("ktc2.lopt", it) }
	}
	if (vSources.isEmpty()) fail("no source file")
	vSources.firstOrNull { !it.isFile }?.let { fail("no such file '$it'") }

	// The C of the last command is kept when nothing it was made from changed: the sources, ktc2 itself, the options.
	val vStampFile = File(vOut, "ktc2_inputs.txt")
	val vStamp = inputsStamp(vSources.toList(), vDumpIr)
	if (vStampFile.isFile && vStampFile.readText() == vStamp && File(vOut, com.bitsycore.ktc2.frontend.kManifestName).isFile) {
		Progress.note("Kotlin → C", "up to date")
	} else {
		vStampFile.delete()
		if (!K2Frontend.compile(vSources.toList(), vOut, vDumpIr)) {
			Progress.fail()
			throw Exit(1)
		}
		vStampFile.writeText(vStamp)
	}
	writeCMake(vOut, vName, vProject)
	if (!vBuild && !vRun && !vCMake) return null
	val vProgram = (if (vUsesCMake) buildWithCMake(vOut, vName, vDebug) else buildProgram(vOut, vName, vDebug)) ?: throw Exit(1)
	if (!vRun) {
		println("ktc2: built $vProgram")
		return null
	}
	return listOf(vProgram.absolutePath) + vProgramArguments
}

/**
 * What a translation is made from, as text: ktc2's own build, the Kotlin it runs, each source file with a digest of its
 * content, the options the command left in system properties, and the `KTC2_` settings. The same text, the same C.
 */
private fun inputsStamp(inSources: List<File>, inDumpIr: Boolean): String {
	val vDigest = java.security.MessageDigest.getInstance("SHA-256")
	fun digestOf(inFile: File): String = vDigest.digest(inFile.readBytes()).joinToString("") { "%02x".format(it) }
	val vTool = File(Progress::class.java.protectionDomain.codeSource.location.toURI())
	return buildList {
		add("ktc2 ${vTool.path} ${vTool.length()} ${vTool.lastModified()}")
		add("kotlin ${org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION}")
		add("dumpIr $inDumpIr")
		kCommandProperties.forEach { add("$it=${System.getProperty(it)}") }
		System.getenv().keys.filter { it.startsWith("KTC2_") }.sorted().forEach { add("$it=${System.getenv(it)}") }
		inSources.map { it.absoluteFile }.sortedBy { it.path }.forEach { add("${it.path} ${digestOf(it)}") }
	}.joinToString("\n", postfix = "\n")
}

/** Adds [inOption] to the newline-separated list held in the system property [inProperty]. */
private fun addOption(inProperty: String, inOption: String) {
	System.setProperty(inProperty, (System.getProperty(inProperty, "") + "\n" + inOption).trim())
}

/** Prints [inMessage] with the usage text and exits with status 2. */
private fun fail(inMessage: String): Nothing {
	System.err.println("ktc2: $inMessage")
	System.err.println(kUsage)
	throw Exit(2)
}
