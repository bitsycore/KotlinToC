package com.bitsycore.ktc2

import com.bitsycore.ktc2.frontend.K2Frontend
import java.io.File
import kotlin.system.exitProcess

private const val kUsage = "Usage: ktc2 <file.kt...> [-o <output_dir>] [--dump-ir] [--warn-heap] [--no-warn-cycles]"

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
			// Silences the report of classes that can form reference cycles.
			vArg == "--no-warn-cycles" -> System.setProperty("ktc2.warnCycles", "false")
			vArg.endsWith(".kt") -> vSources += File(vArg)
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
