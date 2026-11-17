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

private const val kTimeoutSeconds = 300L

/** How long a C compiler may take on one program: the largest, of thousands of files, take a few minutes on a slow machine. */
private const val kCompileTimeoutSeconds = 900L

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
/**
 * `KTC2_TEST_STEPS`: what is done with each program, of `translate` (to C), `jvm` (compiled and
 * run on the JVM, what it prints being what the C must print) and `c` (built with gcc and run).
 * All three by default. A CI job that only needs the C (AddressSanitizer) or what the program
 * prints (MSVC) leaves the others out: the comparison is then made where both are there.
 */
/**
 * The categories of the suite, each run unless `KTC2_TEST_CATEGORIES` names the ones to run (separated by commas):
 * - `integration`: the programs under `tests/integration/`, and the programs of several files under `tests/integration/modules/`;
 * - `kotlin`: the tests of the Kotlin standard library and of Kotlin/Native's runtime (`tests/stdlib/`), and ktc2's own
 *   common tests (`tests/common/`);
 * - `box`: the box tests of the Kotlin compiler (`tests/box/`);
 * - `libraries`: the own tests of the libraries that are bundled modules (`tests/modules/<module>/`): most of the
 *   suite's time, and they change only with the library or its module.
 * The CI job runs `integration` and `kotlin`.
 */
private val kCategories = System.getenv("KTC2_TEST_CATEGORIES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
	?: setOf("integration", "kotlin", "box", "libraries")

/**
 * `KTC2_TEST_NO_JVM`: the categories whose tests run in C only, not compared with the JVM (separated by commas). A file
 * of tests checks itself (its `main` fails when a test fails), so it needs the JVM only to be compared with it.
 */
private val kNoJvm = System.getenv("KTC2_TEST_NO_JVM")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

private val kSteps = System.getenv("KTC2_TEST_STEPS")?.split(',')?.map { it.trim() }?.toSet() ?: setOf("translate", "jvm", "c")

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
 * The C compiler is `clang` (the owner, 2026-11-17: ktc2's first), or the `KTC2_CC` environment variable.
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

	private val mCompiler = System.getenv("KTC2_CC") ?: "clang"

	/**
	 * The test functions `<Name>.skip` next to [inSource] leaves out, one a line with the reason after a colon
	 * (`name: why`, or `Class.name: why`). A line that begins with `msvc ` applies only when the C compiler
	 * targets MSVC's C library (MSVC, or clang on Windows): what differs there, as the last digit of `pow`.
	 */
	private fun skippedOf(inSource: File): Set<String> {
		val vFile = File(inSource.parentFile, inSource.nameWithoutExtension + ".skip").takeIf { it.isFile } ?: return emptySet()
		val vOnMsvc = com.bitsycore.ktc2.interop.targetsMsvc(mCompiler)
		return vFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
			.mapNotNull { vLine -> if (vLine.startsWith("msvc ")) vLine.removePrefix("msvc ").takeIf { vOnMsvc } else vLine }
			.map { it.substringBefore(':').trim() }.filter { it.isNotEmpty() }.toSet()
	}

	/** The thread option and the math library: needed on POSIX systems; on Windows neither exists for every compiler, nor is needed. */
	private val kSystemLibraries = if ("win" in System.getProperty("os.name").lowercase()) emptyList() else listOf("-pthread", "-lm")

	/** The name of the program being checked, as the Gradle task shows it: see [functionsFile]. */
	private var mCurrent = ""

	/** The category of the test being checked (see [kCategories]). */
	private var mCategory = ""

	/** A test of the suite named [inName], of [inCategory]: what [inBody] checks. */
	private fun named(inName: String, inCategory: String, inBody: () -> Unit): DynamicTest = DynamicTest.dynamicTest(inName) {
		mCurrent = inName
		mCategory = inCategory
		mNativeReference = false
		mHasTestFunctions = false
		inBody()
	}

	/**
	 * Where the result of each test function of the program being checked goes, a line each (`ok Name` or
	 * `FAILED Name: reason`): the Gradle task shows them under the program (build.gradle.kts), by the same name.
	 */
	private fun functionsFile(): File = File("build/integration/functions/${mCurrent.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")

	/**
	 * The milliseconds of the steps of the current program, for the Gradle task to show on its line and add up
	 * (build.gradle.kts): `kotlinToIr irToC cToExe run`, written once it has run.
	 */
	private fun stepsFile(): File = File("build/integration/steps/${mCurrent.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")

	@TestFactory
	fun programs(): List<DynamicTest> {
		File(kStatsFile).delete()
		File(kTimesFile).delete()
		// How many this part runs: the Gradle task adds the parts up to show the progress of the run.
		val vCount = File("build/integration/count.$mShard.txt").apply { parentFile.mkdirs() }
		if (mShard >= kShards) {
			vCount.writeText("0")
			return emptyList()
		}
		val vAll = programList()
		// Every part has the whole list: the first to get here says how many the run has, before any program ends.
		File("build/integration/count.total.txt").writeText(vAll.size.toString())
		return vAll.filterIndexed { vIndex, _ -> vIndex % kShards == mShard }.also { vCount.writeText(it.size.toString()) }
	}

	/** True when [inSource] is one of what `KTC2_TEST_FILTER` asks for: parts of a path, separated by commas. */
	private fun selected(inSource: File): Boolean {
		val vFilter = System.getenv("KTC2_TEST_FILTER") ?: return true
		return vFilter.split(',').any { it.isNotEmpty() && it in inSource.invariantSeparatorsPath }
	}

	private fun programList(): List<DynamicTest> =
		(if ("integration" in kCategories) integrationList() + projectList() else emptyList()) +
			(if ("kotlin" in kCategories) commonTestList() else emptyList()) +
			(if ("box" in kCategories) boxTestList() else emptyList()) +
			(if ("libraries" in kCategories) moduleTestList() else emptyList())

	private fun integrationList(): List<DynamicTest> =
		File("tests/integration").walkTopDown()
			// A file in the directory of a module (tests/integration/modules/) is a part of that program (kProjects).
			.filter { it.isFile && it.extension == "kt" && !File(it.parentFile, "module.ktc2.toml").isFile }
			// KTC2_TEST_FILTER=ported/ runs only the programs whose path contains that text (several, separated by commas).
			.filter { vSource -> selected(vSource) }
			.sortedBy { it.path }
			.map { vSource -> named(vSource.relativeTo(File("tests/integration")).invariantSeparatorsPath, "integration") { check(vSource) } }
			.toList()

	// ==================
	// MARK: Common tests
	// ==================

	/**
	 * The files under `tests/stdlib/` and `tests/common/` are tests as Kotlin Multiplatform's common tests are written:
	 * classes with `@Test` functions that assert with `kotlin.test`, and no `main`. They say what
	 * must hold on every platform and nothing of what a platform is free in. The suite writes
	 * the `main` that calls each test ([testMain]), runs the program in C and on the JVM, and
	 * fails when a test fails on either or when the two do not print the same. `tools/native.sh`
	 * runs them with Kotlin/Native, whose own `kotlin.test` they then use.
	 */
	private fun commonTestList(): List<DynamicTest> =
		listOf(File("tests/stdlib"), File("tests/common")).asSequence().flatMap { it.walkTopDown() }
			// tests/stdlib/support holds the helpers of the library's tests; a directory `pending` those that do not pass yet.
			.filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.split('/').none { it == "support" || it == "pending" } }
			.filter { vSource -> selected(vSource) }
			.sortedBy { it.path }
			.map { vSource -> named(vSource.invariantSeparatorsPath, "kotlin") { checkCommon(vSource) } }
			.toList()

	/** `kotlin.test` as ktc2 has it: compiled with a test for the JVM too, so that both run the same assertions. */
	private val kTestLibrary = File("modules/stdlib/Ktc2Test.kt")

	/** The sources compiled for the JVM besides the program's own. */
	private var mJvmExtra: List<File> = emptyList()

	/** Options of the JVM compilation besides the usual ones. */
	private var mJvmOptions: List<String> = emptyList()

	/** What the tests of the standard library opt in to (its `commonTestOptIns`). */
	private val kLibraryOptIns = listOf("kotlin.ExperimentalUnsignedTypes", "kotlin.ExperimentalStdlibApi", "kotlin.io.encoding.ExperimentalEncodingApi", "kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")

	/** A declaration a file of tests leaves to the platform. */
	private val kExpect = Regex("(?m)^(internal |public )?expect .*$")

	/** Sources that are compiled for C only. */
	private var mCExtra: List<File> = emptyList()

	private fun checkCommon(inSource: File) {
		mInput = null
		mWarnings = emptyList()
		// `<Name>.cycles` next to a file of common tests says, with the reason, that it builds objects that
		// refer to each other in a circle: those are never freed (there is no collector of cycles), which
		// for this one file is not a failure.
		mAllowsLeaks = File(inSource.parentFile, inSource.nameWithoutExtension + ".cycles").isFile
		mNativeReference = File(inSource.parentFile, inSource.nameWithoutExtension + ".native").isFile
		mHasTestFunctions = true
		// `<Name>.vary`: the beginnings of the lines it prints that differ on every run (a random seed), one a line.
		mVarying = File(inSource.parentFile, inSource.nameWithoutExtension + ".vary").takeIf { it.isFile }?.readLines()
			?.filter { it.isNotEmpty() && !it.startsWith("#") } ?: emptyList()
		val vOutDir = File("build/integration/test-${inSource.nameWithoutExtension}").apply { deleteRecursively() }
		val vMain = File("build/testmain/${inSource.nameWithoutExtension}Main.kt").apply { parentFile.mkdirs() }
		// `<Name>.skip` names the tests of the file that are not run, one a line with the reason after a colon
		// (`name: why`, or `Class.name: why`): what ktc2 does not do, said where it is left out. Neither side runs them.
		val vSkipped = skippedOf(inSource)
		vMain.writeText(testMain(inSource.readText(), vSkipped))
		// What the tests of the library share (tests/stdlib/support): the common part for both, and for each side
		// its answers to what the library's tests leave to the platform.
		fun support(inPart: String): List<File> = File("tests/stdlib/support/$inPart").listFiles { vFile -> vFile.extension == "kt" }?.sortedBy { it.name } ?: emptyList()
		val vShared = if (inSource.parentFile.name == "stdlib") support("common") else emptyList()
		// `<Name>.with` names other files of tests this one uses something of (a helper declared next to tests): compiled with it, their tests not run.
		val vWith = File(inSource.parentFile, inSource.nameWithoutExtension + ".with").takeIf { it.isFile }?.readLines()
			?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }?.map { File(inSource.parentFile, it) } ?: emptyList()
		mJvmExtra = listOf(kTestLibrary) + if (vShared.isEmpty()) emptyList() else support("jvm")
		mCExtra = if (vShared.isEmpty()) emptyList() else support("ktc2")
		// `<Name>.jvm`: options of the compilation for the JVM that this file needs, one a line, with why in a comment.
		val vJvmOptions = File(inSource.parentFile, inSource.nameWithoutExtension + ".jvm").takeIf { it.isFile }?.readLines()
			?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") } ?: emptyList()
		// The library's tests are of its own module: they opt in to what it calls experimental, and see what it keeps internal.
		mJvmOptions = if (vShared.isEmpty()) emptyList() else kLibraryOptIns.map { "-opt-in=$it" } + vJvmOptions + "-Xmulti-platform" + "-Xfriend-paths=${K2Frontend.userClasspath().split(File.pathSeparator).first()}"
		try {
			// A file that leaves something to the platform (`expect`) is compiled without those lines: tests/stdlib/support answers them for each side.
			val vText = inSource.readText()
			val vSource = if (vShared.isEmpty() || !kExpect.containsMatchIn(vText)) inSource else File("build/testmain/${inSource.name}").apply { writeText(vText.replace(kExpect, "// (answered in tests/stdlib/support)")) }
			verify("tests/${inSource.name}", listOf(vSource, vMain) + vWith + vShared, vOutDir, null)
		} finally {
			mJvmExtra = emptyList()
			mJvmOptions = emptyList()
			mVarying = emptyList()
			mCExtra = emptyList()
			mAllowsLeaks = false
		}
	}

	// ==================
	// MARK: Box tests
	// ==================

	/**
	 * The tests of the Kotlin compiler (`compiler/testData/codegen/box`), which every back end of
	 * Kotlin runs, Kotlin/Native's included: each file has a `fun box(): String` that returns "OK".
	 * They are under `tests/box/`, as they are, with the helpers they import under
	 * `tests/box/helpers/` (`import helpers.*`). The suite writes a `main` that prints what `box()`
	 * returns and fails otherwise, and runs it in C and on the JVM.
	 */
	private fun boxTestList(): List<DynamicTest> =
		File("tests/box").walkTopDown()
			// tests/box/pending holds those that do not pass yet (tests/README.md).
			.filter { it.isFile && it.extension == "kt" && it.parentFile.name != "helpers" && "pending" !in it.invariantSeparatorsPath.split('/') }
			.filter { vSource -> selected(vSource) }
			.sortedBy { it.path }
			.map { vSource -> named(vSource.invariantSeparatorsPath, "box") { checkBox(vSource) } }
			.toList()

	private fun checkBox(inSource: File) {
		mInput = null
		mWarnings = emptyList()
		mAllowsLeaks = File(inSource.parentFile, inSource.nameWithoutExtension + ".cycles").isFile
		// A long name is cut, with a hash of the whole: the C files of the program are named after it, and a path of
		// Windows has at most 260 characters (the checkout of a CI machine is deeper than one's own).
		val vFull = "box-" + inSource.relativeTo(File("tests/box")).invariantSeparatorsPath.removeSuffix(".kt").replace('/', '-')
		val vName = if (vFull.length <= 60) vFull else vFull.take(50) + "-" + vFull.hashCode().toUInt().toString(16)
		val vText = inSource.readText()
		val vPackage = Regex("(?m)^package\\s+([\\w.]+)").find(vText)?.groupValues?.get(1)
		// The name of the file of `main` is the name of its class on the JVM: no '-' there.
		val vMain = File("build/testmain/${vName.replace('-', '_')}.kt").apply { parentFile.mkdirs() }
		vMain.writeText(
			(if (vPackage != null) "package $vPackage\n\n" else "") +
				"fun main() {\n\tval vResult = box()\n\tprintln(vResult)\n\tif (vResult != \"OK\") throw IllegalStateException(\"box() returned \" + vResult)\n}\n"
		)
		val vHelpers = if ("helpers" in vText) File("tests/box/helpers").listFiles { vFile -> vFile.extension == "kt" }?.sortedBy { it.name } ?: emptyList() else emptyList()
		// The tests assert with `kotlin.test`, which is ktc2's own for C and is compiled with them for the JVM.
		mJvmExtra = listOf(kTestLibrary)
		try {
			verify("tests/box/${inSource.relativeTo(File("tests/box")).invariantSeparatorsPath}", listOf(inSource, vMain) + vHelpers, File("build/integration/$vName").apply { deleteRecursively() }, null)
		} finally {
			mJvmExtra = emptyList()
			mAllowsLeaks = false
		}
	}

	// ==================
	// MARK: Tests of a bundled module's library
	// ==================

	/**
	 * The tests of a library that is a bundled module, under `tests/modules/<module>/<library>/` as its repository has them
	 * at the version the module compiles: `commonTest/` and `concurrentTest/` (common to the JVM and Native), and the
	 * helpers of each platform (`nativeTest/`, `jvmTest/`). Each file of tests is a program, compiled with the other
	 * files of those two (their tests not run): in C with the module's sources and `nativeTest`, on the
	 * JVM with the library's jars, which it sees the internals of as a test of the library does, and `jvmTest`.
	 * [kModuleTests] names the modules that have such tests.
	 */
	/** The bundled modules whose library's own tests are under `tests/modules/<module>/`. */
	private val kModuleTests = listOf("kotlinx-io", "kotlinx-collections-immutable", "atomicfu", "kotlinx-datetime", "kotlinx-serialization", "kotlinx-serialization-json-io")

	/** The source sets of a module's tests that are common: run in C and on the JVM. */
	private val kCommonTestSets = Regex("/(commonTest|concurrentTest)/")

	private fun moduleTestList(): List<DynamicTest> =
		kModuleTests.asSequence().flatMap { File("tests/modules/$it").walkTopDown() }
			.filter { it.isFile && it.extension == "kt" && kCommonTestSets.containsMatchIn(it.invariantSeparatorsPath) && "@Test" in it.readText() }
			// `<Name>.pending` next to a file of a library's tests: it does not pass yet, for the reason written in it.
			.filter { !File(it.parentFile, it.nameWithoutExtension + ".pending").isFile }
			.filter { vSource -> selected(vSource) }
			.sortedBy { it.path }
			.map { vSource -> named(vSource.invariantSeparatorsPath, "libraries") { checkModuleTest(vSource) } }
			.toList()

	/**
	 * The bundled module [inName] as ktc2 takes it (copied into [inOutDir], its library's sources from the cache), then the
	 * bundled modules it depends on, each once.
	 */
	private fun bundledWithDependencies(inName: String, inOutDir: File): List<com.bitsycore.ktc2.ModuleFile> {
		val vResult = LinkedHashMap<String, com.bitsycore.ktc2.ModuleFile>()
		fun add(inModule: String) {
			if (inModule in vResult) return
			val vModuleFile = com.bitsycore.ktc2.bundledModule(inModule, inOutDir) ?: fail("no bundled module '$inModule'")
			vResult[inModule] = vModuleFile
			vModuleFile.dependencies.forEach { add(it) }
		}
		add(inName)
		return vResult.values.toList()
	}

	private fun checkModuleTest(inSource: File) {
		mHasTestFunctions = true
		val vSet = kCommonTestSets.find(inSource.invariantSeparatorsPath)!!
		val vRoot = File(inSource.invariantSeparatorsPath.substring(0, vSet.range.first))
		fun sources(inSet: String) = File(vRoot, inSet).walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
		val vCommon = sources("commonTest") + sources("concurrentTest")
		val vModuleName = vRoot.parentFile.name
		// The test's file name and a hash of its path: the build directory has to leave room for the C's own paths (Windows: 260).
		val vRelative = inSource.invariantSeparatorsPath.substring(vSet.range.last + 1).removeSuffix(".kt")
		val vName = vModuleName + "-" + vRelative.substringAfterLast('/') + "-" + "%04x".format(vRelative.hashCode() and 0xFFFF)
		val vOutDir = File("build/integration/$vName").apply { deleteRecursively() }
		mInput = null
		mWarnings = emptyList()
		mAllowsLeaks = File(inSource.parentFile, inSource.nameWithoutExtension + ".cycles").isFile
		val vSkipped = skippedOf(inSource)
		// `<Name>.vary`: the beginnings of the lines it prints that differ on every run (a size drawn at random, a time).
		mVarying = File(inSource.parentFile, inSource.nameWithoutExtension + ".vary").takeIf { it.isFile }?.readLines()
			?.filter { it.isNotEmpty() && !it.startsWith("#") } ?: emptyList()
		val vMain = File("build/testmain/${vName.replace('-', '_')}/Main.kt").apply { parentFile.mkdirs() }
		val vText = inSource.readText()
		val vPackage = Regex("(?m)^package\\s+([\\w.]+)").find(vText)?.groupValues?.get(1)
		vMain.writeText((if (vPackage != null) "package $vPackage\n\n" else "") + testMain(vText, vSkipped))
		// The module as ktc2 takes it (its own files, and its library's sources fetched into the cache), with the bundled
		// modules it depends on (kotlinx-datetime on kotlinx-serialization), and their jars on the JVM.
		val vModules = bundledWithDependencies(vModuleName, vOutDir)
		val vJars = vModules.mapNotNull { System.getProperty("ktc2.jvmModule.${it.name}")?.takeIf { vJar -> vJar.isNotEmpty() } }.joinToString(File.pathSeparator)
		val vFriendJars = System.getProperty("ktc2.jvmModule.$vModuleName", "")
		mCExtra = vModules.flatMap { vModuleFile -> com.bitsycore.ktc2.filesOf(vModuleFile) ?: fail("module '${vModuleFile.name}' could not be had") } +
			sources("nativeTest") + sources("nativeCommonTest")
		mJvmExtra = listOf(kTestLibrary) + sources("jvmTest")
		mJvmOptions = listOf(
			// kotlin.test (ktc2's, compiled with the tests) is common too: the common tests use it.
			"-Xmulti-platform", "-Xcommon-sources=" + (vCommon + kTestLibrary).joinToString(",") { it.path },
			"-Xfriend-paths=" + vFriendJars.split(File.pathSeparator).joinToString(","),
			"-classpath", K2Frontend.userClasspath() + File.pathSeparator + vJars,
		)
		mJvmClasspath = vJars
		System.setProperty("ktc2.weak", vModules.flatMap { it.weak }.joinToString("\n"))
		System.setProperty("ktc2.acyclic", vModules.flatMap { it.acyclic }.joinToString("\n"))
		System.setProperty("ktc2.moduleOptIn", vModules.flatMap { it.optIn }.distinct().joinToString("\n"))
		mJvmOptions = mJvmOptions + vModules.first().optIn.map { "-opt-in=$it" }
		try {
			verify("tests/modules/$vModuleName/${vRoot.name}/${inSource.name}", listOf(inSource, vMain) + (vCommon - inSource), vOutDir, null)
		} finally {
			mCExtra = emptyList()
			mJvmExtra = emptyList()
			mJvmOptions = emptyList()
			mJvmClasspath = ""
			mAllowsLeaks = false
			mVarying = emptyList()
			System.clearProperty("ktc2.weak")
			System.clearProperty("ktc2.acyclic")
			System.clearProperty("ktc2.moduleOptIn")
		}
	}

	/**
	 * Programs of several files and modules: each directory named here has a module file, and is
	 * compiled as `ktc2 <directory>` compiles it. They are plain Kotlin, so they are compared with
	 * the JVM like the single files. The examples (`examples/`) are not here: they are programs for a
	 * desktop, built and run by hand.
	 */
	private val kProjects = listOf("tests/integration/modules/kotlinx-io", "tests/integration/modules/kotlinx-serialization", "tests/integration/modules/kotlinx-datetime", "tests/integration/modules/private-names")

	private fun projectList(): List<DynamicTest> =
		kProjects
			.filter { vPath -> selected(File("projects/$vPath")) }
			.map { vPath -> named("projects/$vPath", "integration") { checkProject(File(vPath)) } }

	private fun checkProject(inDirectory: File) {
		val vName = "project-" + inDirectory.name
		val vOutDir = File("build/integration/$vName").apply { deleteRecursively() }
		val vProject = loadProject(inDirectory, vOutDir) ?: fail("the module in $inDirectory could not be loaded")
		mInput = null
		// `program.cycles` in the program's directory: as `<Name>.cycles` next to a file of tests, with the reason in it.
		mAllowsLeaks = File(inDirectory, "program.cycles").isFile
		System.clearProperty("ktc2.main")
		vProject.main?.let { System.setProperty("ktc2.main", it) }
		// A bundled module made of a library's sources (kotlinx-io) is, on the JVM, that library's jars.
		val vJvmModules = vProject.bundledFiles.keys.mapNotNull { vModule -> System.getProperty("ktc2.jvmModule.$vModule")?.takeIf { it.isNotEmpty() }?.let { vModule to it } }.toMap()
		val vExcluded = vJvmModules.keys.flatMap { vProject.bundledFiles.getValue(it) }.map { it.absoluteFile }.toSet()
		mJvmExcluded = { vSource -> vSource.absoluteFile in vExcluded }
		mJvmClasspath = vJvmModules.values.joinToString(File.pathSeparator)
		// What the modules say is @Weak or @Acyclic, as `ktc2` passes it on.
		System.setProperty("ktc2.weak", vProject.weak.joinToString("\n"))
		System.setProperty("ktc2.acyclic", vProject.acyclic.joinToString("\n"))
		System.setProperty("ktc2.moduleOptIn", vProject.optIn.joinToString("\n"))
		try {
			verify(vName, vProject.sources.toList(), vOutDir, null)
		} finally {
			System.clearProperty("ktc2.main")
			System.clearProperty("ktc2.weak")
			System.clearProperty("ktc2.acyclic")
			System.clearProperty("ktc2.moduleOptIn")
			mJvmExcluded = { false }
			mJvmClasspath = ""
		}
	}

	/** The sources the JVM compilation leaves out: those of a module that is a library's jars there. */
	private var mJvmExcluded: (File) -> Boolean = { false }

	/** What the JVM compilation and run have on their class path besides the usual: those jars. */
	private var mJvmClasspath = ""

	/** The standard input of the program being checked; null when it has none. */
	private var mInput: File? = null

	/** Set while a file of tests that says it makes cycles is checked: what it leaves allocated is not reported. */
	private var mAllowsLeaks = false

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
		// For tools/asan_all.sh and tools/msvc_all.sh: this program may leave cycles allocated (a `.cycles` file).
		if (mAllowsLeaks) File(vOutDir, "cycles-allowed").apply { parentFile.mkdirs() }.writeText("")
		K2Frontend.lastFrontEndNanos = 0
		K2Frontend.lastTranslationNanos = 0
		assertTrue(translate(inSources + mCExtra, vOutDir), "ktc2 failed on $inName")
		lap("translate")
		// Of that, the Kotlin front end and ktc2's own translation; the rest (bindings, writing files) counts with the latter.
		val vTranslated = vTimes.last().substringAfter('=').toLong()
		val vKotlinToIr = (K2Frontend.lastFrontEndNanos / 1_000_000).coerceAtMost(vTranslated)
		if ("jvm" !in kSteps && "c" !in kSteps) return

		val vManifest = File(vOutDir, kManifestName).readLines()
		val vJvmMain = vManifest.first { it.startsWith("jvmMain=") }.substringAfter('=')
		val vCDir = File(vOutDir, "c")
		val vCSources = vManifest.filter { it.startsWith("c=") }.map { it.substringAfter('=') }
		val vCompilerOpts = vManifest.filter { it.startsWith("cflag=") }.map { it.substringAfter('=') }
		val vLinkerOpts = vManifest.filter { it.startsWith("ldflag=") }.map { it.substringAfter('=') }

		val vExpected = if (inExpected != null) {
			inExpected.readText().replace("\r\n", "\n")
		} else if ("jvm" !in kSteps || mCategory in kNoJvm) {
			null
		} else {
			val vJvmDir = File(vOutDir, "jvm")
			val vClasspathOptions = if (mJvmClasspath.isEmpty()) emptyList() else listOf("-classpath", K2Frontend.userClasspath() + File.pathSeparator + mJvmClasspath)
			assertTrue(K2Frontend.compileJvm(inSources.filter { it.extension == "kt" && !mJvmExcluded(it) } + mJvmExtra, vJvmDir, mJvmOptions + vClasspathOptions), "kotlinc failed on $inName")
			lap("jvmCompile")
			val vJava = File(System.getProperty("java.home"), "bin/java").path
			val vClasspath = vJvmDir.path + File.pathSeparator + K2Frontend.userClasspath() + (if (mJvmClasspath.isEmpty()) "" else File.pathSeparator + mJvmClasspath)
			run(listOf(vJava, "-Dstdout.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-cp", vClasspath, vJvmMain), File("."), inAllowsFailure = mNativeReference).also { lap("jvmRun") }
		}

		if ("c" !in kSteps) {
			// What the program has to print, for a job that builds it with another compiler (tools/msvc_all.sh).
			// A file of tests not compared with the JVM checks itself: that job only needs it to end well.
			if (vExpected != null) File(vOutDir, "program.out").writeText(kotlinNames(vExpected))
			else if (mHasTestFunctions) File(vOutDir, "self-checking").writeText("")
			return
		}
		val vExe = File(vOutDir, "program.exe")
		val vOptions = listOf(mCompiler, "-std=c11", "-O1", "-ffp-contract=off", "-DKTC2_LEAK_CHECK", "-DKTC2_ARC_STATS", "-Wall", "-Werror", "-Wno-unused-variable", "-Wno-unused-but-set-variable") + kSystemLibraries.take(1)
		val vAll = File(vCDir, "ktc2_all.c")
		if (kIsOneFile && vAll.isFile) {
			// The program as one C file, and what it does not include (the C of a module).
			val vIncluded = vAll.readLines().filter { it.startsWith("#include \"") }.map { it.substringAfter('"').substringBefore('"') }.toSet()
			run(vOptions + listOf("-I", ".", "-o", vExe.absolutePath) + vCompilerOpts + listOf(vAll.name) + vCSources.filter { it !in vIncluded } + kSystemLibraries.drop(1) + vLinkerOpts, vCDir, kCompileTimeoutSeconds)
		} else if (kCompileJobs <= 1) {
			run(vOptions + listOf("-I", ".", "-o", vExe.absolutePath) + vCompilerOpts + vCSources + kSystemLibraries.drop(1) + vLinkerOpts, vCDir, kCompileTimeoutSeconds)
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
						run(vOptions + listOf("-c", "-I", vCDir.absolutePath) + vCompilerOpts + vFiles.map { File(vCDir, it).absolutePath }, vObjects, kCompileTimeoutSeconds)
					} catch (vError: Throwable) {
						vFailures += vError
					}
				}.also { it.start() }
			}
			vWorkers.forEach { it.join() }
			vFailures.firstOrNull()?.let { throw it }
			// Named from the directory of the program: a command line has a longest length.
			val vObjectFiles = File(vOutDir, "obj").walkTopDown().filter { it.isFile && it.extension == "o" }.map { it.relativeTo(vOutDir).invariantSeparatorsPath }.sorted().toList()
			run(vOptions + listOf("-o", vExe.absolutePath) + vObjectFiles + kSystemLibraries.drop(1) + vLinkerOpts, vOutDir, kCompileTimeoutSeconds)
		}
		lap("cCompile")
		val vCompiled = vTimes.last().substringAfter('=').toLong()
		val vActual = run(listOf(vExe.absolutePath), File("."), inAllowsFailure = true)
		// The result of each test function of a file of tests, for the Gradle task to show under the file (build.gradle.kts),
		// written before a failure of the program is reported, so that the functions that failed are shown with it.
		if (mHasTestFunctions) functionsFile().apply { parentFile.mkdirs() }.writeText(vActual.lineSequence().filter { it.startsWith("ok ") || it.startsWith("FAILED ") }.joinToString("\n"))
		if (mLastExit != 0) fail("exit $mLastExit from ${vExe.absolutePath}\n$mLastErrors\n$vActual")
		lap("cRun")
		stepsFile().apply { parentFile.mkdirs() }.writeText("$vKotlinToIr ${vTranslated - vKotlinToIr} $vCompiled ${vTimes.last().substringAfter('=')}\n")
		// What the C program printed: what `tools/msvc_all.sh` compares MSVC's build with on a machine that has the C and not the program.
		File(vOutDir, "program.out").writeText(kotlinNames(vActual))
		File(kTimesFile).appendText("$inName ${vTimes.joinToString(" ")}\n")

		val vReference = if (vExpected != null && mNativeReference) asOnNative(vExpected, vActual) else vExpected
		if (vReference != null) assertEquals(samePlatform(vReference), samePlatform(vActual), "C output differs from the JVM for $inName")
	}

	/**
	 * True for a file of Kotlin/Native's own tests (`<Name>.native` next to it): ktc2 does as Kotlin/Native does, and
	 * where the JVM differs its run fails those tests. The JVM is the reference only for the others ([asOnNative]).
	 */
	private var mNativeReference = false

	/** True for a file of tests whose `main` the suite wrote: what it prints says how each test function went. */
	private var mHasTestFunctions = false

	/** The exit code and the standard error of the last process [run] ran. */
	private var mLastExit = 0
	private var mLastErrors = ""

	/**
	 * What the JVM printed for a file of Kotlin/Native's tests, [inJvm], with each test the JVM failed and C passed
	 * counted as passed, and the count of failures that follows: what is left to compare is what the two agree on.
	 */
	private fun asOnNative(inJvm: String, inC: String): String {
		val vPassedInC = inC.lines().filter { it.startsWith("ok ") }.map { it.removePrefix("ok ") }.toSet()
		val vLines = inJvm.lines().map { vLine ->
			val vName = vLine.removePrefix("FAILED ").substringBefore(": ")
			if (vLine.startsWith("FAILED ") && vName in vPassedInC) "ok $vName" else vLine
		}
		val vFailed = vLines.count { it.startsWith("FAILED ") }
		return vLines.map { vLine -> Regex("^(\\d+) tests, \\d+ failed$").find(vLine)?.let { "${it.groupValues[1]} tests, $vFailed failed" } ?: vLine }
			.joinToString("\n")
	}

	/** Lines of the output that differ from run to run (`<Name>.vary`): left out of the comparison. */
	private var mVarying: List<String> = emptyList()

	/** Runs a process and returns its stdout with normalized line endings; fails on a non-zero exit. */
	/**
	 * [inOutput] without what differs between platforms and is no difference of behaviour. The
	 * standard exceptions are classes of `java.lang` and `java.util` on the JVM and of `kotlin`
	 * here and on Kotlin/Native: a program that prints one prints either name.
	 */
	private fun samePlatform(inOutput: String): String =
		kotlinNames(inOutput)
			.lines().filter { vLine -> mVarying.none { varies(vLine, it) } }.joinToString("\n")

	/** True when [inLine] is one [inEntry] of a `.vary` file names: by its beginning, or all of it by a `/regex/`. */
	private fun varies(inLine: String, inEntry: String): Boolean =
		if (inEntry.length > 1 && inEntry.startsWith("/") && inEntry.endsWith("/")) Regex(inEntry.substring(1, inEntry.length - 1)).matches(inLine) else inLine.startsWith(inEntry)

	/** [inOutput] with the JVM's names of the standard exceptions (`java.lang.IllegalStateException`) as Kotlin's, which ktc2 uses. */
	private fun kotlinNames(inOutput: String): String = inOutput.replace(Regex("\\bjava\\.(lang|util)\\.(?=[A-Z])"), "kotlin.")

	private fun run(inCommand: List<String>, inDirectory: File, inTimeout: Long = kTimeoutSeconds, inAllowsFailure: Boolean = false): String {
		val vErrors = File.createTempFile("ktc2", ".err")
		try {
			// `<name>.in` next to a program is what it reads from its standard input; without one, there is nothing to read.
			val vBuilder = ProcessBuilder(startable(inCommand, inDirectory)).directory(inDirectory).redirectError(vErrors)
			val vProcess = if (mInput != null) {
				vBuilder.redirectInput(mInput).start()
			} else {
				vBuilder.start().also { it.outputStream.close() }
			}
			// Read by a thread of its own: reading here until the end would wait for a program that never ends, past its time.
			val vBytes = java.util.concurrent.CompletableFuture.supplyAsync { vProcess.inputStream.readBytes() }
			if (!vProcess.waitFor(inTimeout, TimeUnit.SECONDS)) {
				vProcess.destroyForcibly()
				fail("timeout after $inTimeout s: ${inCommand.first()}")
			}
			val vOutput = vBytes.get().decodeToString()
			mLastExit = vProcess.exitValue()
			mLastErrors = vErrors.readText()
			if (vProcess.exitValue() != 0 && !inAllowsFailure) {
				fail("exit ${vProcess.exitValue()} from ${inCommand.joinToString(" ")}\n${vErrors.readText()}\n$vOutput")
			}
			// Reference-count statistics of each program, for comparing optimizations: build/integration/arc-stats.txt
			vErrors.readLines().firstOrNull { it.startsWith("ktc2: arc:") }?.let { vLine ->
				File(kStatsFile).appendText("${File(inCommand.first()).parentFile.name}: ${vLine.removePrefix("ktc2: arc: ")}\n")
			}
			val vLeaks = vErrors.readLines().filter { it.startsWith("ktc2: leak") }
			if (vLeaks.isNotEmpty() && !mAllowsLeaks) fail("${inCommand.first()} leaked:\n${vLeaks.joinToString("\n")}")
			return vOutput.replace("\r\n", "\n")
		} finally {
			vErrors.delete()
		}
	}
}

/**
 * The `main` that runs the tests of a file of common tests, given its text [inSource]: each
 * `@Test` function of each class on a new object of the class, between its `@BeforeTest` and
 * `@AfterTest` functions, and each `@Test` function that is in no class. A test that is
 * `@Ignore`d, or in a class that is, is left out. It prints a line for each test, and fails
 * when one of them did.
 */
fun testMain(inSource: String, inSkipped: Set<String> = emptySet()): String {
	val vPackage = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE).find(inSource)?.groupValues?.get(1)
	val vPrefix = if (vPackage == null) "" else "$vPackage."
	class TestClass(val name: String?, val isAbstract: Boolean = false, val parent: String? = null) {
		val tests = ArrayList<String>()
		val before = ArrayList<String>()
		val after = ArrayList<String>()
	}
	val vClasses = ArrayList<TestClass>()
	var vCurrent = TestClass(null).also { vClasses += it }
	val vTopLevel = vCurrent
	var vMarks = HashSet<String>()
	var vIsIgnoredClass = false
	// The classes nested in a top-level one (`class Formats` inside `LocalDateSamples`), with their indentation: `Outer.Inner`.
	val vNested = ArrayList<Pair<Int, TestClass>>()
	var vOuter: TestClass? = null
	// What is in a comment is no test.
	val vCode = inSource.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
	for (vLine in vCode.lines()) {
		val vText = vLine.trim().substringBefore("//")
		Regex("^@(Test|Ignore|BeforeTest|AfterTest)\\b").findAll(vText).forEach { vMarks += it.groupValues[1] }
		// Marks may share a line with what they mark.
		Regex("@(Test|Ignore|BeforeTest|AfterTest)\\b").findAll(vText).forEach { vMarks += it.groupValues[1] }
		val vIndent = vLine.length - vLine.trimStart().length
		val vNestedClass = if (vIndent > 0 && vOuter != null && '{' in vText) Regex("^(?:(?:internal|public|private|open|abstract|final)\\s+)*class\\s+(\\w+)").find(vText) else null
		if (vNestedClass != null) {
			while (vNested.isNotEmpty() && vNested.last().first >= vIndent) vNested.removeAt(vNested.size - 1)
			val vName = (vNested.lastOrNull()?.second ?: vOuter!!).name + "." + vNestedClass.groupValues[1]
			vCurrent = TestClass(vName, Regex("\\babstract\\b").containsMatchIn(vText.substringBefore("class"))).also { vClasses += it }
			vNested += vIndent to vCurrent
			vMarks = HashSet()
			continue
		}
		if (vNested.isNotEmpty() && vText.startsWith("}") && vIndent <= vNested.last().first) {
			vNested.removeAt(vNested.size - 1)
			vCurrent = vNested.lastOrNull()?.second ?: vOuter!!
			continue
		}
		val vClass = Regex("^(?:(?:internal|public|open|abstract|final)\\s+)*class\\s+(\\w+)").find(vLine)
		if (vClass != null) {
			vIsIgnoredClass = "Ignore" in vMarks
			// The class it extends, when that is a class of tests of this file: its tests are this one's too.
			val vParent = Regex("\\)\\s*:\\s*(\\w+)\\s*[<(]").findAll(vLine).lastOrNull()?.groupValues?.get(1)
				?: Regex("class\\s+\\w+\\s*:\\s*(\\w+)").find(vLine)?.groupValues?.get(1)
			vCurrent = TestClass(vClass.groupValues[1], Regex("\\babstract\\b").containsMatchIn(vLine.substringBefore("class")), vParent).also { vClasses += it }
			vOuter = vCurrent
			vNested.clear()
			vMarks = HashSet()
			continue
		}
		if (vLine.startsWith("}")) {
			vCurrent = vTopLevel
			vOuter = null
			vNested.clear()
			vIsIgnoredClass = false
		}
		val vFunction = Regex("\\bfun\\s+(`[^`]+`|\\w+)\\s*\\(").find(vText)
		if (vFunction != null) {
			val vName = vFunction.groupValues[1]
			when {
				"Ignore" in vMarks || vIsIgnoredClass || vName.trim('`') in inSkipped || "${vCurrent.name}.${vName.trim('`')}" in inSkipped -> {}
				"Test" in vMarks -> vCurrent.tests += vName
				"BeforeTest" in vMarks -> vCurrent.before += vName
				"AfterTest" in vMarks -> vCurrent.after += vName
			}
			vMarks = HashSet()
		}
	}
	return buildString {
		append("// Written by the suite: runs the tests of the file next to it.\n\n")
		append("fun main() {\n\tvar vCount = 0\n\tvar vFailed = 0\n")
		append("\tfun test(inName: String, inBody: () -> Unit) {\n\t\tvCount++\n\t\ttry {\n\t\t\tinBody()\n\t\t\tprintln(\"ok \$inName\")\n")
		append("\t\t} catch (inError: Throwable) {\n\t\t\tvFailed++\n\t\t\tprintln(\"FAILED \$inName: \$inError\")\n\t\t}\n\t}\n")
		// A class runs the tests it inherits; one that is abstract runs none itself.
		fun line(inClass: TestClass): List<TestClass> =
			generateSequence(inClass) { vClass -> vClasses.firstOrNull { it.name != null && it.name == vClass.parent } }.take(8).toList().reversed()
		for (vOwn in vClasses) {
			if (vOwn.isAbstract) continue
			val vLine = line(vOwn)
			val vClass = TestClass(vOwn.name)
			vLine.forEach { vClass.tests += it.tests; vClass.before += it.before; vClass.after += it.after }
			for (vTest in vClass.tests.distinct()) {
				val vShown = vTest.trim('`')
				if (vClass.name == null) {
					append("\ttest(\"$vShown\") { $vPrefix$vTest() }\n")
				} else {
					append("\ttest(\"${vClass.name}.$vShown\") {\n\t\tval vTest = $vPrefix${vClass.name}()\n")
					vClass.before.forEach { append("\t\tvTest.$it()\n") }
					append("\t\ttry {\n\t\t\tvTest.$vTest()\n\t\t} finally {\n")
					vClass.after.forEach { append("\t\t\tvTest.$it()\n") }
					append("\t\t}\n\t}\n")
				}
			}
		}
		append("\tprintln(\"\$vCount tests, \$vFailed failed\")\n")
		append("\tif (vFailed > 0) throw IllegalStateException(\"\$vFailed of \$vCount tests failed\")\n}\n")
	}
}
