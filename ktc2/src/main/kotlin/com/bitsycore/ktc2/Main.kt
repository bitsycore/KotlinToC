package com.bitsycore.ktc2

import com.bitsycore.ktc2.frontend.K2Frontend
import java.io.File
import kotlin.system.exitProcess

private const val kUsage = "Usage: ktc2 <file.kt | library.def | directory>... [-o <output_dir>] [-copt <c option>]... [-lopt <linker option>]... [--dump-ir] [--warn-heap] [--no-warn-cycles] [--checks=abort]"

/**
 * Entry point of the ktc2 transpiler (Kotlin semantics on ARC — see ktc2/DESIGN.md).
 * Writes the generated C under `<out>/c` and the JVM reference classes under `<out>/jvm`.
 */
fun main(args: Array<String>) {
	val vSources = mutableListOf<File>()
	var vOutDir = File("out")
	var vDumpIr = false
	var vIndex = 0
	while (vIndex < args.size) {
		val vArg = args[vIndex++]
		when {
			vArg == "-o" && vIndex < args.size -> vOutDir = File(args[vIndex++])
			vArg == "--dump-ir" -> vDumpIr = true
			// Lists every allocation that stays on the heap, with the reason.
			vArg == "--warn-heap" -> System.setProperty("ktc2.warnHeap", "true")
			// An index out of bounds or a division by zero ends the program instead of throwing: no test after such operations.
			vArg == "--checks=abort" -> System.setProperty("ktc2.checks", "abort")
			vArg == "--checks=throw" -> System.setProperty("ktc2.checks", "throw")
			// Silences the report of classes that can form reference cycles.
			vArg == "--no-warn-cycles" -> System.setProperty("ktc2.warnCycles", "false")
			// A Kotlin/Native interop definition: bindings for the C library it names are generated.
			vArg.endsWith(".kt") || vArg.endsWith(".def") -> vSources += File(vArg)
			// An option for the C compiler when it reads the headers of .def files and compiles the result; one for the linker.
			vArg == "-copt" && vIndex < args.size -> System.setProperty("ktc2.copt", (System.getProperty("ktc2.copt", "") + "\n" + args[vIndex++]).trim())
			vArg == "-lopt" && vIndex < args.size -> System.setProperty("ktc2.lopt", (System.getProperty("ktc2.lopt", "") + "\n" + args[vIndex++]).trim())
			// A directory stands for the sources and definitions in it: a module.
			File(vArg).isDirectory -> vSources += File(vArg).listFiles { vFile -> vFile.isFile && (vFile.extension == "kt" || vFile.extension == "def") }!!.sortedBy { it.name }
			else -> fail("unknown argument '$vArg'")
		}
	}
	if (vSources.isEmpty()) fail("no source file")
	vSources.firstOrNull { !it.isFile }?.let { fail("no such file '$it'") }

	if (!K2Frontend.compile(vSources, vOutDir, vDumpIr)) exitProcess(1)
}

/** Prints [inMessage] with the usage line and exits with status 2. */
private fun fail(inMessage: String): Nothing {
	System.err.println("ktc2: $inMessage")
	System.err.println(kUsage)
	exitProcess(2)
}
