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
  --checks=abort     an index out of bounds or a division by zero ends the program instead of throwing
  --warn-heap        list every allocation left on the heap, with the reason
  --no-warn-cycles   do not report classes that can form reference cycles
  --dump-ir          also write the IR received from the Kotlin compiler, and ktc2's own after each pass (kir/)"""

/**
 * Entry point of the ktc2 transpiler (Kotlin semantics on ARC - see DESIGN.md).
 * Writes the generated C under `<out>/c` and a `CMakeLists.txt` for it; with `--build`,
 * `--cmake` or `--run` also the program.
 */
fun main(args: Array<String>) {
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
			vArg == "-o" && vIndex < args.size -> vOutDir = File(args[vIndex++])
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
			vArg == "-lopt" && vIndex < args.size -> addOption("ktc2.lopt", args[vIndex++])
			vArg.endsWith(".kt") || vArg.endsWith(".def") -> vSources += File(vArg)
			File(vArg, kModuleFileName).isFile -> vModules += File(vArg)
			// A directory without a module file stands for the sources and definitions directly in it.
			File(vArg).isDirectory -> vSources += File(vArg).listFiles { vFile -> vFile.isFile && (vFile.extension == "kt" || vFile.extension == "def") }!!.sortedBy { it.name }
			else -> fail("unknown argument '$vArg'")
		}
	}

	val vOut = vOutDir ?: vModules.firstOrNull()?.let { File(it, "build") } ?: File("out")
	var vName = vSources.firstOrNull { it.extension == "kt" }?.nameWithoutExtension ?: "program"
	val vProjects = vModules.map { loadProject(it, vOut) ?: exitProcess(1) }
	// The first module is the program: its name, and where it starts.
	val vProject = vProjects.firstOrNull()
	if (vProject != null) {
		vName = vProject.executable
		vProject.main?.let { System.setProperty("ktc2.main", it) }
	}
	val vUsesCMake = vCMake || vProjects.any { it.needsCMake }
	for (vEach in vProjects) {
		vSources += vEach.sources
		vEach.compilerOpts.forEach { addOption("ktc2.copt", it) }
		(if (vUsesCMake) vEach.linkerOpts else vEach.directLinkerOpts).forEach { addOption("ktc2.lopt", it) }
	}
	if (vSources.isEmpty()) fail("no source file")
	vSources.firstOrNull { !it.isFile }?.let { fail("no such file '$it'") }

	if (!K2Frontend.compile(vSources.toList(), vOut, vDumpIr)) exitProcess(1)
	writeCMake(vOut, vName, vProject)
	if (!vBuild && !vRun && !vCMake) return
	val vProgram = (if (vUsesCMake) buildWithCMake(vOut, vName, vDebug) else buildProgram(vOut, vName, vDebug)) ?: exitProcess(1)
	if (!vRun) {
		println("ktc2: built $vProgram")
		return
	}
	val vProcess = ProcessBuilder(listOf(vProgram.absolutePath) + vProgramArguments).inheritIO().start()
	exitProcess(vProcess.waitFor())
}

/** Adds [inOption] to the newline-separated list held in the system property [inProperty]. */
private fun addOption(inProperty: String, inOption: String) {
	System.setProperty(inProperty, (System.getProperty(inProperty, "") + "\n" + inOption).trim())
}

/** Prints [inMessage] with the usage text and exits with status 2. */
private fun fail(inMessage: String): Nothing {
	System.err.println("ktc2: $inMessage")
	System.err.println(kUsage)
	exitProcess(2)
}
