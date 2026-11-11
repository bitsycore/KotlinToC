package com.bitsycore.ktc2.k2spike

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.File
import kotlin.system.exitProcess

/** System property carrying the report directory to the plugin (loaded in its own classloader). */
const val OUT_DIR_PROPERTY = "ktc2.spike.outDir"

/**
 * Runs the K2 compiler in-process on the given Kotlin files with [SpikeExtension] attached, so the
 * resolved IR can be inspected. Usage: `run --args="samples/Sample.kt samples/StdOverride.kt"`.
 */
fun main(args: Array<String>) {
	val vSources = args.ifEmpty { arrayOf("samples/Sample.kt") }
	val vOutDir = File("build/spike").apply { deleteRecursively(); mkdirs() }
	val vPluginJar = System.getProperty("ktc2.spike.pluginJar") ?: error("ktc2.spike.pluginJar not set")
	val vClasspath = listOf(Unit::class.java, ktc2.Struct::class.java)
		.joinToString(File.pathSeparator) { File(it.protectionDomain.codeSource.location.toURI()).path }
	System.setProperty(OUT_DIR_PROPERTY, vOutDir.absolutePath)

	val vStart = System.nanoTime()
	val vExit = K2JVMCompiler().exec(
		System.err,
		*vSources,
		"-Xallow-kotlin-package",
		"-d", File(vOutDir, "classes").path,
		"-no-stdlib", "-no-reflect",
		"-cp", vClasspath,
		"-Xplugin=$vPluginJar",
	)
	val vMillis = (System.nanoTime() - vStart) / 1_000_000
	println("K2 exit=$vExit in ${vMillis} ms, reports in $vOutDir")
	if (vExit != ExitCode.OK) exitProcess(1)
}
