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
 * How many processes share the programs of a run: Gradle starts one per class below, each
 * with its own Java machine and Kotlin compiler (`ktc2.testShards`, set in `build.gradle.kts`;
 * 1 when the class is run by itself, from an IDE say, and then it takes every program).
 */
private val kShards = (System.getProperty("ktc2.testShards")?.toIntOrNull() ?: 1).coerceIn(1, 12)

/**
 * Whether each program gets the dumps of its IR and KIR next to its C (`ir.txt`, `kir/`): 25 MB
 * a program, so not in a run of the whole suite. A run of a part of it (`KTC2_TEST_FILTER`)
 * has them, since that is how a failure is looked at; `KTC2_TEST_DUMP=1` or `=0` says so directly.
 */
private val kDumps = System.getenv("KTC2_TEST_DUMP")?.let { it != "0" } ?: (System.getenv("KTC2_TEST_FILTER") != null)

/** How many C compilers work on one program at once (`KTC2_TEST_JOBS`; 1: one compiler for all its files). */
/** `KTC2_TEST_UNITY=1`: the C of a program is compiled as one file, `ktc2_all.c`, by one compiler. */
private val kIsOneFile = System.getenv("KTC2_TEST_UNITY") == "1"

private val kCompileJobs = (System.getenv("KTC2_TEST_JOBS")?.toIntOrNull() ?: 4).coerceIn(1, 16)

/*
The parts of the suite: see [IntegrationPart]. Twelve is the most Gradle is asked for. Gradle
deals the classes out to its processes in the order of their names: with these names the
first ones, which are the ones that have programs when there are fewer processes than
classes, go to different processes.
*/
class IntegrationTest : IntegrationPart(0)
class IntegrationTest01 : IntegrationPart(1)
class IntegrationTest02 : IntegrationPart(2)
class IntegrationTest03 : IntegrationPart(3)
class IntegrationTest04 : IntegrationPart(4)
class IntegrationTest05 : IntegrationPart(5)
class IntegrationTest06 : IntegrationPart(6)
class IntegrationTest07 : IntegrationPart(7)
class IntegrationTest08 : IntegrationPart(8)
class IntegrationTest09 : IntegrationPart(9)
class IntegrationTest10 : IntegrationPart(10)
class IntegrationTest11 : IntegrationPart(11)

/**
 * Dual-run oracle: every program under `integration/` is compiled by ktc2 to C and, separately,
 * as ordinary Kotlin/JVM against the real standard library; both are executed and must print
 * the same text.
 *
 * The C compiler is `gcc`, or the `KTC2_CC` environment variable.
 *
 * The programs are independent, and most of the time of one is the Kotlin front end, which
 * works on one program at a time in a process. So the suite is cut in parts, one per class
 * (`IntegrationTest`, `IntegrationTest01`…), which Gradle runs in as many processes: part
 * [mShard] takes every program whose place in the sorted list is [mShard] modulo the number
 * of parts. Each part writes its counts and times to files of its own, which the Gradle task
 * puts together when all are done.
 */
abstract class IntegrationPart(private val mShard: Int) {

	/** One line per program of the last run: how long each step took. */
	private val kTimesFile = "build/integration/times.$mShard.txt"

	/** The counts of allocations and of reference counting of each program of this part. */
	private val kStatsFile = "build/integration/arc-stats.$mShard.txt"

	private val mCompiler = System.getenv("KTC2_CC") ?: "gcc"

	/** The thread option and the math library: needed on POSIX systems; on Windows neither exists for every compiler, nor is needed. */
	private val kSystemLibraries = if ("win" in System.getProperty("os.name").lowercase()) emptyList() else listOf("-pthread", "-lm")

	@TestFactory
	fun programs(): List<DynamicTest> {
		File(kStatsFile).delete()
		File(kTimesFile).delete()
		if (mShard >= kShards) return emptyList()
		return programList().filterIndexed { vIndex, _ -> vIndex % kShards == mShard }
	}

	private fun programList(): List<DynamicTest> =
		File("integration").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			// KTC2_TEST_FILTER=ported/ runs only the programs whose path contains that text.
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
		// A program that must be refused: `<name>.errors` holds a part of each error it has to get.
		val vErrors = File(inSource.parentFile, inSource.nameWithoutExtension + ".errors")
		if (vErrors.isFile) {
			refused(inSource, vOutDir, vErrors.readLines().map { it.trim() }.filter { it.isNotEmpty() })
			return
		}
		// A program using C interop cannot run on the JVM: its output is compared with `<name>.expected`.
		val vExpectedFile = File(inSource.parentFile, inSource.nameWithoutExtension + ".expected")
		// `<name>.warnings` holds a part of each warning ktc2 has to give for the program.
		val vWarnings = File(inSource.parentFile, inSource.nameWithoutExtension + ".warnings")
		mWarnings = if (vWarnings.isFile) vWarnings.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
		verify(inSource.path, listOf(inSource) + listOfNotNull(vDefinition.takeIf { it.isFile }), vOutDir, vExpectedFile.takeIf { it.isFile })
	}

	/** What ktc2 has to say while it accepts the program being checked. */
	private var mWarnings: List<String> = emptyList()

	/** Translates [inSources] and checks that ktc2 said each of [mWarnings] meanwhile. */
	private fun translate(inSources: List<File>, inOutDir: File): Boolean {
		if (mWarnings.isEmpty()) return K2Frontend.compile(inSources, inOutDir, inDumpIr = kDumps)
		val vSaved = System.err
		val vCaptured = java.io.ByteArrayOutputStream()
		val vAccepted: Boolean
		System.setErr(java.io.PrintStream(vCaptured, true, "UTF-8"))
		try {
			vAccepted = K2Frontend.compile(inSources, inOutDir, inDumpIr = kDumps)
		} finally {
			System.setErr(vSaved)
		}
		val vSaid = vCaptured.toString("UTF-8")
		vSaved.print(vSaid)
		for (vMessage in mWarnings) assertTrue(vMessage in vSaid, "ktc2 did not warn '$vMessage':\n$vSaid")
		mWarnings = emptyList()
		return vAccepted
	}

	/** Checks that ktc2 refuses [inSource], and says each of [inExpected] (a part of one of its messages) when it does. */
	private fun refused(inSource: File, inOutDir: File, inExpected: List<String>) {
		val vSaved = System.err
		val vCaptured = java.io.ByteArrayOutputStream()
		val vAccepted: Boolean
		System.setErr(java.io.PrintStream(vCaptured, true, "UTF-8"))
		try {
			vAccepted = K2Frontend.compile(listOf(inSource), inOutDir, inDumpIr = false)
		} finally {
			System.setErr(vSaved)
		}
		val vSaid = vCaptured.toString("UTF-8")
		assertTrue(!vAccepted, "ktc2 accepted ${inSource.path}, which it has to refuse")
		for (vMessage in inExpected) assertTrue(vMessage in vSaid, "ktc2 refused ${inSource.path} without saying '$vMessage':\n$vSaid")
	}

	/**
	 * Translates [inSources] to C, builds and runs the program, and compares what it prints with
	 * [inExpected] or, without one, with what the same sources print on the JVM.
	 */
	private fun verify(inName: String, inSources: List<File>, inOutDir: File, inExpected: File?) {
		val vOutDir = inOutDir
		// Where the time of this program goes: build/integration/times.txt, in milliseconds.
		val vTimes = ArrayList<String>()
		var vSince = System.nanoTime()
		fun lap(inWhat: String) {
			val vNow = System.nanoTime()
			vTimes += "$inWhat=${(vNow - vSince) / 1000000}"
			vSince = vNow
		}
		assertTrue(translate(inSources, vOutDir), "ktc2 failed on $inName")
		lap("translate")

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
			lap("jvmCompile")
			val vJava = File(System.getProperty("java.home"), "bin/java").path
			val vClasspath = vJvmDir.path + File.pathSeparator + K2Frontend.userClasspath()
			run(listOf(vJava, "-Dstdout.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-cp", vClasspath, vJvmMain), File(".")).also { lap("jvmRun") }
		}

		val vExe = File(vOutDir, "program.exe")
		val vOptions = listOf(mCompiler, "-std=c11", "-O1", "-ffp-contract=off", "-DKTC2_LEAK_CHECK", "-DKTC2_ARC_STATS", "-Wall", "-Werror", "-Wno-unused-variable", "-Wno-unused-but-set-variable") + kSystemLibraries.take(1)
		val vAll = File(vCDir, "ktc2_all.c")
		if (kIsOneFile && vAll.isFile) {
			// The program as one C file, and what it does not include (the C of a module).
			val vIncluded = vAll.readLines().filter { it.startsWith("#include \"") }.map { it.substringAfter('"').substringBefore('"') }.toSet()
			run(vOptions + listOf("-I", ".", "-o", vExe.absolutePath) + vCompilerOpts + listOf(vAll.name) + vCSources.filter { it !in vIncluded } + kSystemLibraries.drop(1) + vLinkerOpts, vCDir)
		} else if (kCompileJobs <= 1) {
			run(vOptions + listOf("-I", ".", "-o", vExe.absolutePath) + vCompilerOpts + vCSources + kSystemLibraries.drop(1) + vLinkerOpts, vCDir)
		} else {
			// The C files in a few groups, each compiled by a process of its own into a directory of its own
			// (two files of the same name in different packages go to different groups), then linked.
			val vGroups = List(kCompileJobs) { ArrayList<String>() }
			vCSources.forEachIndexed { vIndex, vSource ->
				val vName = File(vSource).name
				val vGroup = (0 until kCompileJobs).map { vGroups[(vIndex + it) % kCompileJobs] }.firstOrNull { vOthers -> vOthers.none { File(it).name == vName } }
					?: fail("more than $kCompileJobs C files are called $vName")
				vGroup += vSource
			}
			val vFailures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
			val vWorkers = vGroups.withIndex().filter { it.value.isNotEmpty() }.map { (vIndex, vFiles) ->
				val vObjects = File(vOutDir, "obj/$vIndex").apply { mkdirs() }
				Thread {
					try {
						run(vOptions + listOf("-c", "-I", vCDir.absolutePath) + vCompilerOpts + vFiles.map { File(vCDir, it).absolutePath }, vObjects)
					} catch (vError: Throwable) {
						vFailures += vError
					}
				}.also { it.start() }
			}
			vWorkers.forEach { it.join() }
			vFailures.firstOrNull()?.let { throw it }
			// Named from the directory of the program: a command line has a longest length.
			val vObjectFiles = File(vOutDir, "obj").walkTopDown().filter { it.isFile && it.extension == "o" }.map { it.relativeTo(vOutDir).invariantSeparatorsPath }.sorted().toList()
			run(vOptions + listOf("-o", vExe.absolutePath) + vObjectFiles + kSystemLibraries.drop(1) + vLinkerOpts, vOutDir)
		}
		lap("cCompile")
		val vActual = run(listOf(vExe.absolutePath), File("."))
		lap("cRun")
		File(kTimesFile).appendText("$inName ${vTimes.joinToString(" ")}\n")

		assertEquals(samePlatform(vExpected), samePlatform(vActual), "C output differs from the JVM for $inName")
	}

	/** Runs a process and returns its stdout with normalized line endings; fails on a non-zero exit. */
	/**
	 * [inOutput] without what differs between platforms and is no difference of behaviour. The
	 * standard exceptions are classes of `java.lang` and `java.util` on the JVM and of `kotlin`
	 * here and on Kotlin/Native: a program that prints one prints either name.
	 */
	private fun samePlatform(inOutput: String): String =
		inOutput.replace(Regex("\\bjava\\.(lang|util)\\.(?=[A-Z])"), "kotlin.")

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
				File(kStatsFile).appendText("${File(inCommand.first()).parentFile.name}: ${vLine.removePrefix("ktc2: arc: ")}\n")
			}
			val vLeaks = vErrors.readLines().filter { it.startsWith("ktc2: leak") }
			if (vLeaks.isNotEmpty()) fail("${inCommand.first()} leaked:\n${vLeaks.joinToString("\n")}")
			return vOutput.replace("\r\n", "\n")
		} finally {
			vErrors.delete()
		}
	}
}
