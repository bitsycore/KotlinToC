package com.bitsycore.ktc2

import com.bitsycore.ktc2.frontend.K2Frontend
import java.io.File
import kotlin.system.exitProcess

private const val kUsage = """Usage: ktc2 <module directory | file.kt | library.def>... [options] [-- program arguments]
  -o <dir>           where the output goes (default: <module>/build, or ./out)
  --build            also compile the C into a program (C compiler: KTC2_CC, else clang, gcc or cc)
  --cmake            build with CMake, from the CMakeLists.txt written next to the C
                     (always so when a module has a CMake file; KTC2_CMAKE_ARGS: more arguments)
  --run              build, then run the program with the arguments after --
  --debug            build without optimization, with debug information and the leak report
  --universal        on macOS, one program for both arm64 and x86_64 (the setting build.macosArch: either or both)
  -copt <option>     an option for the C compiler; -lopt <option> one for the linker
  -ea                assert() checks (-enable-assertions, as Kotlin/Native's; off otherwise, --debug included)
  --checks=abort     an index out of bounds or a division by zero ends the program instead of throwing
  --warn-heap        list every allocation left on the heap, with the reason
  --no-warn-cycles   do not report classes that can form reference cycles
  --dump-ir          also write the IR received from the Kotlin compiler, and ktc2's own after each pass (kir/)
  -q, --quiet        no line for each step (fetching, translating, compiling); KTC2_QUIET=1 the same
  --set <key=value>  a setting for this command only (ktc2 --settings lists them)
  --settings         print every setting, its value, and where the value comes from
  --stop-daemon      end the compiler process that ktc2 keeps running between commands (KTC2_DAEMON=0: none is used)

Every setting comes from the first of: the command (--set, or the option above that stands for it), its KTC2_
environment variable, the configuration file (KTC2_CONFIG, else config/ktc2.toml of the installation), the default."""

/** Ends the command being run, with the status it exits with. */
private class Exit(val status: Int) : RuntimeException()

/** The options a command leaves in system properties: forgotten before the next one. */
private val kCommandProperties = listOf("ktc2.warnHeap", "ktc2.checks", "ktc2.warnCycles", "ktc2.copt", "ktc2.lopt", "ktc2.main", "ktc2.weak", "ktc2.acyclic", "ktc2.cycles", "ktc2.moduleOptIn")

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
	// Whether a process stays to serve commands is a setting too: of the configuration, the environment or `--set`.
	Settings.reset()
	args.forEachIndexed { vIndex, vArg ->
		if (vArg == "--set" && args.getOrNull(vIndex + 1)?.startsWith("daemon.") == true) {
			val vSetting = args[vIndex + 1]
			Settings.set(vSetting.substringBefore('='), vSetting.substringAfter('=', ""))
		}
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
	Progress.isShown = false
	Settings.reset()?.let { vProblem ->
		System.err.println("ktc2: $vProblem")
		if (vProblem.startsWith("error")) return 2 to null
	}
	return try {
		0 to command(args, inDirectory)
	} catch (vExit: Exit) {
		vExit.status to null
	} catch (vError: Toml.Error) {
		System.err.println("ktc2: error: ${vError.message}")
		1 to null
	}
}

private fun command(args: Array<String>, inDirectory: File?): List<String>? {
	// Without `.` or `..` in it: a path the Kotlin compiler reports a file by is the same as the one given (`ktc2 .`).
	fun path(inText: String): File = File(inText).let { if (inDirectory == null || it.isAbsolute) it else File(inDirectory, inText) }.absoluteFile.normalize()
	val vSources = LinkedHashSet<File>()
	val vModules = ArrayList<File>()
	var vOutDir: File? = null
	var vBuild = false
	var vRun = false
	var vShowSettings = false
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
			vArg == "--quiet" || vArg == "-q" -> Settings.set("output.quiet", true)
			vArg == "--build" -> vBuild = true
			vArg == "--cmake" -> Settings.set("build.cmake", true)
			vArg == "--run" -> vRun = true
			vArg == "--debug" -> Settings.set("build.debug", true)
			vArg == "--universal" -> Settings.set("build.macosArch", "arm64,x86_64")
			vArg == "--dump-ir" -> Settings.set("debug.dumpIr", true)
			vArg == "--warn-heap" -> Settings.set("warnings.heap", true)
			vArg == "--checks=abort" || vArg == "--checks=throw" -> Settings.set("checks.failure", vArg.substringAfter('='))
			vArg == "--no-warn-cycles" -> Settings.set("warnings.cycles", false)
			vArg == "-copt" && vIndex < args.size -> Settings.set("build.compilerOpts", args[vIndex++])
			// As Kotlin/Native's compiler: assertions are checked when asked for, in any build.
			vArg == "-ea" || vArg == "-enable-assertions" || vArg == "--enable-assertions" -> Settings.set("checks.assertions", true)
			vArg == "-lopt" && vIndex < args.size -> Settings.set("build.linkerOpts", args[vIndex++])
			vArg == "--set" && vIndex < args.size -> {
				val vSetting = args[vIndex++]
				if ('=' !in vSetting) fail("--set wants key=value, not '$vSetting'")
				if (!Settings.set(vSetting.substringBefore('=').trim(), vSetting.substringAfter('='))) throw Exit(2)
			}
			vArg == "--settings" -> vShowSettings = true
			vArg.endsWith(".kt") || vArg.endsWith(".def") -> vSources += path(vArg)
			File(path(vArg), kModuleFileName).isFile -> vModules += path(vArg)
			// A directory without a module file stands for the sources and definitions directly in it.
			path(vArg).isDirectory -> vSources += path(vArg).listFiles { vFile -> vFile.isFile && (vFile.extension == "kt" || vFile.extension == "def") }!!.sortedBy { it.name }
			else -> fail("unknown argument '$vArg'")
		}
	}
	if (vShowSettings) {
		print(Settings.describe())
		return null
	}
	// What the settings say, for the parts of ktc2 that read it from the command's properties.
	Progress.isShown = !Settings.flag("output.quiet")
	val vDumpIr = Settings.flag("debug.dumpIr")
	val vDebug = Settings.flag("build.debug")
	val vCMake = Settings.flag("build.cmake")
	when (val vChecks = Settings.text("checks.failure")) {
		"throw", "abort" -> System.setProperty("ktc2.checks", vChecks)
		else -> fail("checks.failure is throw or abort, not '$vChecks'")
	}
	if (Settings.flag("warnings.heap")) System.setProperty("ktc2.warnHeap", "true")
	if (!Settings.flag("warnings.cycles")) System.setProperty("ktc2.warnCycles", "false")
	if (Settings.flag("checks.assertions")) addOption("ktc2.copt", "-DKTC2_ASSERTIONS")
	Settings.list("build.compilerOpts").forEach { addOption("ktc2.copt", it) }
	Settings.list("build.linkerOpts").forEach { addOption("ktc2.lopt", it) }

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
	// One module a line, under the step's name.
	Progress.note("modules", vModulesUsed.joinToString("") { "\n\t$it" })
	val vUsesCMake = vCMake || vProjects.any { it.needsCMake }
	// The program's own files: those given as they are, and those of the modules named on the command line, not of their dependencies.
	val vOwnSources = vSources.toSet() + vProjects.flatMap { it.ownSources }
	for (vEach in vProjects) {
		vSources += vEach.sources
		vEach.compilerOpts.forEach { addOption("ktc2.copt", it) }
		vEach.weak.forEach { addOption("ktc2.weak", it) }
		vEach.acyclic.forEach { addOption("ktc2.acyclic", it) }
		vEach.cycles.forEach { addOption("ktc2.cycles", it) }
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
		if (!K2Frontend.compile(vSources.toList(), vOut, vDumpIr, vOwnSources)) {
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
 * content, the options the command left in system properties, and the settings. The same text, the same C.
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
		addAll(Settings.stamp())
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
