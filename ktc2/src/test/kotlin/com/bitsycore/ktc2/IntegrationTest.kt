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
			// KTC2_TEST_FILTER=v1/ runs only the programs whose path contains that text.
			.filter { vSource -> System.getenv("KTC2_TEST_FILTER")?.let { it in vSource.invariantSeparatorsPath } ?: true }
			.sortedBy { it.path }
			.map { vSource -> DynamicTest.dynamicTest(vSource.relativeTo(File("integration")).invariantSeparatorsPath) { check(vSource) } }
			.toList() + projectList()

	/**
	 * Programs of several files and modules: each directory named here has a module file, and is
	 * compiled as `ktc2 <directory>` compiles it. They are plain Kotlin, so they are compared with
	 * the JVM like the single files.
	 */
	private val kProjects = listOf("examples/showcase/core")

	private fun projectList(): List<DynamicTest> =
		kProjects
			.filter { vPath -> System.getenv("KTC2_TEST_FILTER")?.let { it in "projects/$vPath" } ?: true }
			.map { vPath -> DynamicTest.dynamicTest("projects/$vPath") { checkProject(File(vPath)) } }

	private fun checkProject(inDirectory: File) {
		val vName = "project-" + inDirectory.name
		val vOutDir = File("build/integration/$vName").apply { deleteRecursively() }
		val vProject = loadProject(inDirectory, vOutDir) ?: fail("the module in $inDirectory could not be loaded")
		mInput = null
		System.clearProperty("ktc2.main")
		vProject.main?.let { System.setProperty("ktc2.main", it) }
		try {
			verify(vName, vProject.sources.toList(), vOutDir, null)
		} finally {
			System.clearProperty("ktc2.main")
		}
	}

	/** The standard input of the program being checked; null when it has none. */
	private var mInput: File? = null

	private fun check(inSource: File) {
		mInput = File(inSource.parentFile, inSource.nameWithoutExtension + ".in").takeIf { it.isFile }
		val vOutDir = File("build/integration/${inSource.nameWithoutExtension}").apply { deleteRecursively() }
		// `<name>.def` next to the program describes a C library it uses.
		val vDefinition = File(inSource.parentFile, inSource.nameWithoutExtension + ".def")
		// A program using C interop cannot run on the JVM: its output is compared with `<name>.expected`.
		val vExpectedFile = File(inSource.parentFile, inSource.nameWithoutExtension + ".expected")
		verify(inSource.path, listOf(inSource) + listOfNotNull(vDefinition.takeIf { it.isFile }), vOutDir, vExpectedFile.takeIf { it.isFile })
	}

	/**
	 * Translates [inSources] to C, builds and runs the program, and compares what it prints with
	 * [inExpected] or, without one, with what the same sources print on the JVM.
	 */
	private fun verify(inName: String, inSources: List<File>, inOutDir: File, inExpected: File?) {
		val vOutDir = inOutDir
		assertTrue(K2Frontend.compile(inSources, vOutDir, inDumpIr = true), "ktc2 failed on $inName")

		val vManifest = File(vOutDir, kManifestName).readLines()
		val vJvmMain = vManifest.first { it.startsWith("jvmMain=") }.substringAfter('=')
		val vCDir = File(vOutDir, "c")
		val vCSources = vManifest.filter { it.startsWith("c=") }.map { it.substringAfter('=') }
		val vCompilerOpts = vManifest.filter { it.startsWith("cflag=") }.map { it.substringAfter('=') }
		val vLinkerOpts = vManifest.filter { it.startsWith("ldflag=") }.map { it.substringAfter('=') }

		val vExpected = if (inExpected != null) {
			inExpected.readText().replace("\r\n", "\n")
		} else {
			val vJvmDir = File(vOutDir, "jvm")
			assertTrue(K2Frontend.compileJvm(inSources.filter { it.extension == "kt" }, vJvmDir), "kotlinc failed on $inName")
			val vJava = File(System.getProperty("java.home"), "bin/java").path
			val vClasspath = vJvmDir.path + File.pathSeparator + K2Frontend.userClasspath()
			run(listOf(vJava, "-Dstdout.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-cp", vClasspath, vJvmMain), File("."))
		}

		val vExe = File(vOutDir, "program.exe")
		run(listOf(mCompiler, "-std=c11", "-O1", "-DKTC2_LEAK_CHECK", "-DKTC2_ARC_STATS", "-Wall", "-Werror","-Wno-unused-variable", "-Wno-unused-but-set-variable", "-pthread", "-I", ".", "-o", vExe.absolutePath) + vCompilerOpts + vCSources + "-lm" + vLinkerOpts, vCDir)
		val vActual = run(listOf(vExe.absolutePath), File("."))

		assertEquals(vExpected, vActual, "C output differs from the JVM for $inName")
	}

	/** Runs a process and returns its stdout with normalized line endings; fails on a non-zero exit. */
	private fun run(inCommand: List<String>, inDirectory: File): String {
		val vErrors = File.createTempFile("ktc2", ".err")
		try {
			// `<name>.in` next to a program is what it reads from its standard input; without one, there is nothing to read.
			val vBuilder = ProcessBuilder(inCommand).directory(inDirectory).redirectError(vErrors)
			val vProcess = if (mInput != null) {
				vBuilder.redirectInput(mInput).start()
			} else {
				vBuilder.start().also { it.outputStream.close() }
			}
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
