package com.bitsycore.ktc2

import com.bitsycore.ktc2.frontend.K2Frontend
import com.bitsycore.ktc2.frontend.kManifestName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val kTimeoutSeconds = 60L

/**
 * Dual-run oracle: every program under `integration/` is compiled by ktc2 to C and, separately,
 * as ordinary Kotlin/JVM against the real standard library; both are executed and must print
 * the same text.
 *
 * The C compiler is `gcc`, or the `KTC2_CC` environment variable.
 */
class IntegrationTest {

	private val mCompiler = System.getenv("KTC2_CC") ?: "gcc"

	@TestFactory
	fun programs(): List<DynamicTest> {
		File("build/integration/arc-stats.txt").delete()
		return programList()
	}

	private fun programList(): List<DynamicTest> =
		File("integration").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.sortedBy { it.path }
			.map { vSource -> DynamicTest.dynamicTest(vSource.relativeTo(File("integration")).invariantSeparatorsPath) { check(vSource) } }
			.toList()

	private fun check(inSource: File) {
		val vOutDir = File("build/integration/${inSource.nameWithoutExtension}").apply { deleteRecursively() }
		// `<name>.def` next to the program describes a C library it uses.
		val vDefinition = File(inSource.parentFile, inSource.nameWithoutExtension + ".def")
		assertTrue(K2Frontend.compile(listOf(inSource) + listOfNotNull(vDefinition.takeIf { it.isFile }), vOutDir, inDumpIr = true), "ktc2 failed on $inSource")

		val vManifest = File(vOutDir, kManifestName).readLines()
		val vJvmMain = vManifest.first { it.startsWith("jvmMain=") }.substringAfter('=')
		val vCDir = File(vOutDir, "c")
		val vCSources = vManifest.filter { it.startsWith("c=") }.map { it.substringAfter('=') }
		val vCompilerOpts = vManifest.filter { it.startsWith("cflag=") }.map { it.substringAfter('=') }
		val vLinkerOpts = vManifest.filter { it.startsWith("ldflag=") }.map { it.substringAfter('=') }

		// A program using C interop cannot run on the JVM: its output is compared with `<name>.expected`.
		val vExpectedFile = File(inSource.parentFile, inSource.nameWithoutExtension + ".expected")
		val vExpected = if (vExpectedFile.isFile) {
			vExpectedFile.readText().replace("\r\n", "\n")
		} else {
			val vJvmDir = File(vOutDir, "jvm")
			assertTrue(K2Frontend.compileJvm(listOf(inSource), vJvmDir), "kotlinc failed on $inSource")
			val vJava = File(System.getProperty("java.home"), "bin/java").path
			val vClasspath = vJvmDir.path + File.pathSeparator + K2Frontend.userClasspath()
			run(listOf(vJava, "-Dstdout.encoding=UTF-8", "-cp", vClasspath, vJvmMain), File("."))
		}

		val vExe = File(vOutDir, "program.exe")
		run(listOf(mCompiler, "-std=c11", "-O1", "-DKTC2_LEAK_CHECK", "-DKTC2_ARC_STATS", "-Wall", "-Werror","-Wno-unused-variable", "-Wno-unused-but-set-variable", "-pthread", "-I", ".", "-o", vExe.absolutePath) + vCompilerOpts + vCSources + "-lm" + vLinkerOpts, vCDir)
		val vActual = run(listOf(vExe.absolutePath), File("."))

		assertEquals(vExpected, vActual, "C output differs from the JVM for $inSource")
	}

	/** Runs a process and returns its stdout with normalized line endings; fails on a non-zero exit. */
	private fun run(inCommand: List<String>, inDirectory: File): String {
		val vErrors = File.createTempFile("ktc2", ".err")
		try {
			val vProcess = ProcessBuilder(inCommand).directory(inDirectory).redirectError(vErrors).start()
			val vOutput = vProcess.inputStream.readBytes().decodeToString()
			if (!vProcess.waitFor(kTimeoutSeconds, TimeUnit.SECONDS)) {
				vProcess.destroyForcibly()
				fail("timeout: ${inCommand.first()}")
			}
			if (vProcess.exitValue() != 0) {
				fail("exit ${vProcess.exitValue()} from ${inCommand.joinToString(" ")}\n${vErrors.readText()}\n$vOutput")
			}
			// Reference-count statistics of each program, for comparing optimizations: build/integration/arc-stats.txt
			vErrors.readLines().firstOrNull { it.startsWith("ktc2: arc:") }?.let { vLine ->
				File("build/integration/arc-stats.txt").appendText("${File(inCommand.first()).parentFile.name}: ${vLine.removePrefix("ktc2: arc: ")}\n")
			}
			val vLeaks = vErrors.readLines().filter { it.startsWith("ktc2: leak") }
			if (vLeaks.isNotEmpty()) fail("${inCommand.first()} leaked:\n${vLeaks.joinToString("\n")}")
			return vOutput.replace("\r\n", "\n")
		} finally {
			vErrors.delete()
		}
	}
}
