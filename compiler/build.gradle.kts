import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

plugins {
	// Every version is in gradle/libs.versions.toml.
	alias(libs.plugins.kotlin.jvm)
	application
}

group = "com.bitsycore"
version = "0.1-SNAPSHOT"

/*
The compiler: ktc2 itself (src/main/kotlin), its test harness (src/test/kotlin), and what the installed
tool carries as resources from the other parts of the repository: the platform layer and the C runtime
(stdlib/), the bundled libraries (modules/), the sources of the Kotlin library and of the libraries that Gradle
fetches. Its build directory is the repository's `build/`, where every tool and document looks
(build/install/ktc2, build/integration...). Paths below are from the repository's root.
*/
layout.buildDirectory.set(rootProject.layout.buildDirectory)
val kRoot: File = rootDir

// The tool is `ktc2`, as before the compiler was a project of its own: build/install/ktc2/bin/ktc2.
base {
	archivesName.set("ktc2")
}

distributions {
	main {
		distributionBaseName.set("ktc2")
	}
}

application {
	applicationName = "ktc2"
	mainClass.set("com.bitsycore.ktc2.MainKt")
	// A compilation is one short run: the quick JIT tier and a heap that does not have to grow make it a quarter faster.
	// With the quick tier alone the JVM reserves a small code cache, which the Kotlin compiler can fill: it is given room.
	applicationDefaultJvmArgs = listOf("-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=256m", "-XX:+UseParallelGC", "-Xms512m")
}

tasks.named<JavaExec>("run") {
	dependsOn("unpackStdlibSources")
	workingDir = kRoot
	systemProperty("ktc2.stdlibDir", layout.buildDirectory.dir("stdlib-src").get().asFile.absolutePath)
	// Each run through Gradle is its own process: no compiler process is left behind.
	environment("KTC2_DAEMON", "0")
}

repositories {
	mavenCentral()
}

/** The Kotlin standard library sources (Apache-2), compiled together with user programs. */
val stdlibSources: Configuration = configurations.create("stdlibSources") {
	isTransitive = false
}

/**
 * The sources of the standard library for Wasm (Apache-2), for the part of them ktc2 takes:
 * `commonNonJvmMain` and `nativeWasmMain`, which Kotlin/Native compiles too and which no other
 * published jar holds (`NumberConverter`, the Unicode tables and the code that reads them, the regex
 * engine...), and the few files of `wasmCommonMain` that the regex engine needs.
 */
val nativeWasmSources: Configuration = configurations.create("nativeWasmSources") {
	isTransitive = false
}

/**
 * A bundled module (modules/<name>) that takes a library as it is: ktc2 itself fetches the library's sources (its
 * `upstream`, at its `version`), and the suite compiles the JVM side of a program on the module with the library's
 * jars, which its `jvm` names and Gradle fetches here.
 */
class LibraryModule(val name: String, val jvm: List<String>) {
	val jvmConfiguration: Configuration = configurations.create("moduleJvm-$name") { isTransitive = false }
}

/** The bundled modules, from their module files: those with JVM jars, each with them at its version. */
val kLibraryModules: List<LibraryModule> = kRoot.resolve("modules").listFiles { vFile -> File(vFile, "module.ktc2.toml").isFile }!!.sortedBy { it.name }.map { vDirectory ->
	val vText = File(vDirectory, "module.ktc2.toml").readText()
	fun values(inKey: String): List<String> {
		val vLine = Regex("(?ms)^$inKey\\s*=\\s*(\\[.*?\\]|\"[^\"]*\")").find(vText)?.groupValues?.get(1) ?: return emptyList()
		return Regex("\"([^\"]*)\"").findAll(vLine).map { it.groupValues[1] }.toList()
	}
	val vVersion = values("version").firstOrNull()
	// `group:artifact` is at the module's version; `group:artifact:version` at its own.
	LibraryModule(vDirectory.name, values("jvm").map { if (it.count { vChar -> vChar == ':' } == 1) "$it:$vVersion" else it })
}


dependencies {
	stdlibSources(variantOf(libs.kotlin.stdlib) { classifier("sources") })
	nativeWasmSources(variantOf(libs.kotlin.stdlib.wasm.js) { classifier("sources") })
	for (vModule in kLibraryModules) {
		for (vJar in vModule.jvm) add(vModule.jvmConfiguration.name, vJar)
	}
	// The `ktc2.` package on the JVM (compat/): on the class path of every compilation, and what the suite's JVM side runs with.
	implementation(project(":compat"))
	// Frontend: the Kotlin K2 compiler. Pinned - its IR API is experimental.
	implementation(libs.kotlin.compiler.embeddable)
	// Run with ktc2's plugin in the compilations of programs that use kotlinx.serialization (K2Frontend.pluginClasspath).
	implementation(libs.kotlin.serialization.plugin)
	testImplementation(kotlin("test"))
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	jvmToolchain(21)
}

val unpackStdlibSources = tasks.register("unpackStdlibSources", Sync::class) {
	description = "Unpack the Kotlin standard library sources (Apache-2) into the build directory, so that they can be bundled with user programs."
	from({ stdlibSources.map { zipTree(it) } })
	from({ nativeWasmSources.map { zipTree(it) } }) {
		// wasmCommonMain: of it, only what Kotlin/Native's regex engine needs and its runtime has in C++ (BitSet's actual,
		// the canonical decomposition of characters), here in Kotlin as Wasm has it.
		include("commonNonJvmMain/**", "nativeWasmMain/**", "wasmCommonMain/kotlin/text/regex/**")
		// Kotlin/Native's Regex as it is, with a cleaner: its pattern's graph has cycles (a quantified group's end points
		// back at the quantifier), which Native's collector frees and reference counting does not. The cleaner cuts them
		// when the Regex ends (Ktc2RegexCycles.kt, which has the `actual`).
		filesMatching("nativeWasmMain/kotlin/text/Regex.kt") {
			filter { vLine ->
				when (vLine) {
					"import kotlin.text.regex.*" -> vLine + "\n\n/** ktc2: what cuts the cycles of [pattern] when its Regex ends (Ktc2RegexCycles.kt). */\n" +
						"internal expect fun ktc2RegexCleaner(pattern: Pattern): Any"
					"    private val startNode = nativePattern.startNode" -> vLine + "\n\n    // ktc2: see ktc2RegexCleaner.\n" +
						"    private val ktc2Cleaner = ktc2RegexCleaner(nativePattern)"
					else -> vLine
				}
			}
		}
	}
	into(layout.buildDirectory.dir("stdlib-src"))
}

/** The installed ktc2 carries the standard library sources it compiles with every program (those of files.txt). */
tasks.processResources {
	dependsOn(unpackStdlibSources)
	val vListed = kRoot.resolve("stdlib/files.txt").readLines().map { it.trim() }.filter { it.contains('/') && !it.startsWith("#") }
	inputs.file(kRoot.resolve("stdlib/files.txt"))
	from(layout.buildDirectory.dir("stdlib-src")) {
		include(vListed)
		into("ktc2/kotlin-stdlib")
	}
	// ktc2's platform layer, a directory of its own (stdlib/) that an IDE sees as Kotlin (ide/), carried as resources;
	// with it the C runtime (stdlib/runtime) and the C library's bindings (stdlib/interop), where the compiler reads them.
	from(kRoot.resolve("stdlib")) {
		exclude("runtime/**", "interop/**")
		into("ktc2/stdlib")
	}
	from(kRoot.resolve("stdlib/runtime")) {
		into("ktc2")
	}
	from(kRoot.resolve("stdlib/interop")) {
		into("ktc2/interop")
	}
	// The bundled libraries (modules/<name>: module.ktc2.toml, ktc2's own files, .def and CMake files).
	from(kRoot.resolve("modules")) {
		into("ktc2/modules")
	}
}

tasks.test {
	useJUnitPlatform()
	// The suite reads its programs and tests from the repository (tests/) and writes into build/integration.
	workingDir = kRoot
	dependsOn(unpackStdlibSources)
	systemProperty("ktc2.stdlibDir", layout.buildDirectory.dir("stdlib-src").get().asFile.absolutePath)
	// A program that uses a bundled module made of a library's sources is compiled for the JVM with the library's JVM jars.
	for (vModule in kLibraryModules) systemProperty("ktc2.jvmModule.${vModule.name}", vModule.jvmConfiguration.files.joinToString(File.pathSeparator))
	// The integration programs and the bundled runtime are inputs of the oracle test.
	inputs.dir(kRoot.resolve("tests"))
	// The programs are shared between several processes (see IntegrationTest): KTC2_TEST_FORKS says how many,
	// at most 12; by default half the processors but no more than 8, past which they only get in each other's way. Each has a Java machine and a Kotlin compiler of its own.
	val vForks = (System.getenv("KTC2_TEST_FORKS")?.toIntOrNull() ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(8)).coerceIn(1, 12)
	maxParallelForks = vForks
	systemProperty("ktc2.testShards", vForks.toString())
	// Each process analyses the library once, for all its programs (KTC2_KEEP_LIBRARY=0: once per program).
	systemProperty("ktc2.keepLibrary", "true")
	// What the tests of the standard library opt in to besides what the library itself does (tests/README.md).
	systemProperty("ktc2.optIn", "kotlin.ExperimentalUnsignedTypes,kotlin.io.encoding.ExperimentalEncodingApi,kotlin.uuid.ExperimentalUuidApi,kotlin.time.ExperimentalTime")
	// A program compiled with the library sources and not on the kept library takes more than 1 GB on Linux (kotlinx-datetime
	// with kotlinx-serialization), more than 1.5 (kotlinx.serialization's JSON tests: JSON, its io, kotlinx-io).
	maxHeapSize = "2g"
	// Several compilers at once: each keeps to a few threads of its own, or they get in each other's way.
	jvmArgs((System.getenv("KTC2_TEST_JVM") ?: "-XX:+UseParallelGC -XX:ParallelGCThreads=2 -XX:CICompilerCount=2 -Xms1g").split(" ").filter { it.isNotEmpty() })
	// What each process wrote, put together: the counts (tools/arc_compare.py) and the times of each step.
	val vResults = layout.buildDirectory.dir("integration").get().asFile
	doLast {
		for (vName in listOf("arc-stats", "times")) {
			val vParts = (0 until 12).map { File(vResults, "$vName.$it.txt") }.filter { it.isFile }
			val vLines = vParts.flatMap { it.readLines() }.filter { it.isNotBlank() }.sorted()
			File(vResults, "$vName.txt").writeText(vLines.joinToString("\n", postfix = "\n"))
			vParts.forEach { it.delete() }
		}
	}
	// A line for each program as it ends, with the progress of the run: what passed in green, what failed in red with its
	// reason and what it printed. KTC2_TEST_QUIET=1 prints only the failures and the summary; NO_COLOR=1 leaves the colors out.
	// From when the tests start, not from when the build was configured.
	val vStart = AtomicLong(System.nanoTime())
	doFirst {
		vResults.listFiles { vFile -> vFile.name.startsWith("count.") }?.forEach { it.delete() }
		File(vResults, "functions").deleteRecursively()
		File(vResults, "steps").deleteRecursively()
		vStart.set(System.nanoTime())
	}
	testLogging { events(); showStandardStreams = false }
	val vQuiet = System.getenv("KTC2_TEST_QUIET") == "1"
	val vColor = System.getenv("NO_COLOR") == null
	fun paint(inCode: String, inText: String) = if (vColor) "\u001b[${inCode}m$inText\u001b[0m" else inText
	val vOutputs = ConcurrentHashMap<String, StringBuilder>()
	val vDone = AtomicInteger()
	val vFailed = ConcurrentLinkedQueue<String>()
	fun keyOf(inTest: TestDescriptor) = "${inTest.className}/${inTest.displayName}"
	fun total(): Int = File(vResults, "count.total.txt").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
		?: vResults.listFiles { vFile -> vFile.name.startsWith("count.") }?.sumOf { it.readText().trim().toIntOrNull() ?: 0 } ?: 0
	// What kind of test a program is, from its name: shown on its line, and counted apart in the summary.
	// The categories of IntegrationTest (KTC2_TEST_CATEGORIES), in the order the summary shows them.
	val kKinds = listOf("integration" to "integration tests", "kotlin" to "Kotlin tests", "box" to "box tests", "libraries" to "library tests")
	fun kindOf(inName: String): String = when {
		inName.startsWith("tests/box/") -> "box"
		inName.startsWith("tests/modules/") -> "libraries"
		inName.startsWith("tests/") -> "kotlin"
		else -> "integration"
	}
	val vKindColors = mapOf("box" to "35", "libraries" to "36", "kotlin" to "34", "integration" to "37")
	val vByKind = ConcurrentHashMap<String, IntArray>()
	// The steps of a program, each program's on its line and their totals in the summary (IntegrationTest.stepsFile):
	// the Kotlin front end, ktc2's translation of its IR to C, the C compiler and linker, and the run of the program.
	val kStepNames = listOf("Kotlin → IR", "IR → C", "C → exe", "run")
	val vStepTotals = Array(kStepNames.size) { AtomicLong() }
	fun seconds(inMillis: Long): String = if (inMillis >= 60_000) "${inMillis / 60_000} min ${inMillis % 60_000 / 1000} s" else "%.1f s".format(Locale.ROOT, inMillis / 1000.0)
	addTestOutputListener(object : TestOutputListener {
		override fun onOutput(inTest: TestDescriptor, inEvent: TestOutputEvent) {
			vOutputs.getOrPut(keyOf(inTest)) { StringBuilder() }.append(inEvent.message)
		}
	})
	addTestListener(object : TestListener {
		override fun beforeSuite(inSuite: TestDescriptor) {}
		override fun beforeTest(inTest: TestDescriptor) {}
		override fun afterSuite(inSuite: TestDescriptor, inResult: TestResult) {
			if (inSuite.parent != null) return
			val vSeconds = (System.nanoTime() - vStart.get()) / 1_000_000_000
			val vPassed = inResult.successfulTestCount
			val vLine = "${vPassed} passed, ${inResult.failedTestCount} failed, ${inResult.skippedTestCount} skipped in ${vSeconds / 60} min ${vSeconds % 60} s"
			println()
			println(if (inResult.failedTestCount == 0L) paint("1;32", "✅ $vLine") else paint("1;31", "❌ $vLine"))
			for ((vKind, vLabel) in kKinds) {
				val vCounts = vByKind[vKind] ?: continue
				val vText = "   $vLabel".padEnd(22) + "${vCounts[0]} passed" + if (vCounts[1] > 0) ", ${vCounts[1]} failed" else ""
				println(if (vCounts[1] > 0) paint("31", vText) else paint(vKindColors.getValue(vKind), vText))
			}
			// Where the time went, added up over the programs (several run at once: more than the run's own time).
			if (vStepTotals.any { it.get() > 0 }) {
				println(paint("1", "   time of each step, all programs together:"))
				kStepNames.forEachIndexed { vIndex, vName -> println(paint("2", "      ${vName.padEnd(14)}${seconds(vStepTotals[vIndex].get())}")) }
			}
			vFailed.forEach { println(paint("31", "   ❌ $it")) }
		}
		override fun afterTest(inTest: TestDescriptor, inResult: TestResult) {
			// The parts' own test (`programs()`) is the container of the programs: only the programs are counted.
			if (inTest.displayName == "programs()") return
			val vKind = kindOf(inTest.displayName)
			vByKind.getOrPut(vKind) { IntArray(2) }.let { if (inResult.resultType == TestResult.ResultType.FAILURE) it[1]++ else it[0]++ }
			val vTag = paint(vKindColors.getValue(vKind), vKind.padEnd(11))
			val vIndex = vDone.incrementAndGet()
			val vTotal = total().coerceAtLeast(vIndex)
			val vWidth = vTotal.toString().length
			val vProgress = paint("2", "[${vIndex.toString().padStart(vWidth)}/$vTotal ${(vIndex * 100 / vTotal).toString().padStart(3)}%]")
			val vTime = paint("2", "%5.1f s".format(Locale.ROOT, (inResult.endTime - inResult.startTime) / 1000.0))
			val vOutput = vOutputs.remove(keyOf(inTest))
			// A file of tests runs several test functions: each is a line under the file, as an IDE shows them.
			// Written by the test, by the name of the program (IntegrationTest.functionsFile).
			val vFunctionsFile = File(vResults, "functions/${inTest.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")
			val vFunctions = vFunctionsFile.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()
			vFunctionsFile.delete()
			val vFunctionsFailed = vFunctions.count { it.startsWith("FAILED ") }
			val vCounts = if (vFunctions.isEmpty()) "" else paint("2", " (${vFunctions.size - vFunctionsFailed}/${vFunctions.size} tests)")
			// The milliseconds of its four steps, when it got to the end of them.
			val vStepsFile = File(vResults, "steps/${inTest.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")
			val vSteps = vStepsFile.takeIf { it.isFile }?.readText()?.trim()?.split(' ')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == kStepNames.size }
			vStepsFile.delete()
			vSteps?.forEachIndexed { vIndex, vMillis -> vStepTotals[vIndex].addAndGet(vMillis) }
			val vStepText = if (vSteps == null) "" else paint("2", "  " + kStepNames.indices.joinToString(" · ") { "${kStepNames[it]} ${"%.1f".format(Locale.ROOT, vSteps[it] / 1000.0)}" })
			// As an IDE shows them: each class of tests, then its test functions under it. Only for a file where one
			// failed (all of them then), or for every file with KTC2_TEST_FUNCTIONS=1.
			fun functions() {
				var vClass: String? = null
				for (vFunction in vFunctions) {
					val vPassed = vFunction.startsWith("ok ")
					val vText = vFunction.removePrefix("ok ").removePrefix("FAILED ")
					val vName = vText.substringBefore(": ")
					val vOwner = vName.substringBeforeLast('.', "")
					val vShort = if (vOwner.isEmpty()) vText else vText.removePrefix("$vOwner.")
					if (vOwner.isNotEmpty() && vOwner != vClass) {
						vClass = vOwner
						println("      ${paint("1", vOwner)}")
					}
					val vIndent = if (vOwner.isEmpty()) "      " else "         "
					println(if (vPassed) "$vIndent${paint("32", "✔")} ${paint("2", vShort)}" else "$vIndent${paint("31", "✘")} ${paint("31", vShort)}")
				}
			}
			when (inResult.resultType) {
				TestResult.ResultType.SUCCESS -> if (!vQuiet) {
					println("$vProgress $vTag ${paint("32", "✔")} ${inTest.displayName}$vCounts $vTime$vStepText")
					if (System.getenv("KTC2_TEST_FUNCTIONS") == "1") functions()
				}
				TestResult.ResultType.SKIPPED -> if (!vQuiet) println("$vProgress $vTag ${paint("33", "⏭")} ${inTest.displayName}")
				else -> {
					vFailed += inTest.displayName
					println("$vProgress $vTag ${paint("1;31", "✘ ${inTest.displayName}")}$vCounts $vTime")
					functions()
					// The reason, as the assertion has it, and what the program wrote: a few lines of each.
					inResult.exceptions.firstOrNull()?.message?.lines()?.take(12)?.forEach { println(paint("31", "      $it")) }
					vOutput?.lines()?.filter { it.isNotBlank() }?.take(20)?.forEach { println(paint("2", "      | $it")) }
				}
			}
		}
	})
	// Gradle's own report of the failures stays: build/reports/tests/test/index.html.
	reports.html.required.set(true)
}

// ==================
// MARK: Comparison with Kotlin/Native and the JVM (tools/compare.py)
// ==================

/** The JVM's Kotlin compiler, as ktc2 runs it, and the Kotlin library's jar: what tools/compare.py builds the JVM side with. */
val kotlincJvm: Configuration = configurations.create("kotlincJvm")

dependencies {
	"kotlincJvm"(libs.kotlin.compiler.embeddable)
}

tasks.register("compareClasspath") {
	description = "Write the class path of the JVM's Kotlin compiler and the Kotlin library's jar for tools/compare.py."
	val vCompiler: FileCollection = kotlincJvm
	val vStdlib: FileCollection = kotlincJvm.filter { it.name.startsWith("kotlin-stdlib-") && !it.name.contains("common") }
	val vOut = layout.buildDirectory.file("compare/kotlinc.classpath")
	inputs.files(vCompiler)
	outputs.file(vOut)
	doLast {
		vOut.get().asFile.apply { parentFile.mkdirs() }.writeText(vCompiler.files.joinToString(File.pathSeparator) + "\n" + vStdlib.files.first().path + "\n")
	}
}

/**
 * Kotlin/Native's compiler of the Kotlin version ktc2 follows, put under ~/.konan as Kotlin/Native's own tools put it,
 * for tools/compare.py and tools/native.sh. Its first compilation fetches the rest it needs (LLVM, the system's libraries).
 */
val kotlinNativeCompiler: Configuration = configurations.create("kotlinNativeCompiler") { isTransitive = false }

dependencies {
	val vHost = System.getProperty("os.name").lowercase().let { vName ->
		val vArm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
		when {
			"win" in vName -> "windows-x86_64"
			"mac" in vName -> if (vArm) "macos-aarch64" else "macos-x86_64"
			else -> if (vArm) "linux-aarch64" else "linux-x86_64"
		}
	}
	val vExtension = if (vHost.startsWith("windows")) "zip" else "tar.gz"
	"kotlinNativeCompiler"("org.jetbrains.kotlin:kotlin-native-prebuilt:${libs.versions.kotlin.get()}:$vHost@$vExtension")
}

tasks.register<Copy>("fetchKotlinNative") {
	description = "Fetch Kotlin/Native's compiler of the Kotlin version ktc2 follows into ~/.konan."
	val vArchive: FileCollection = kotlinNativeCompiler
	from({ vArchive.files.map { if (it.name.endsWith(".zip")) zipTree(it) else tarTree(it) } })
	into(File(System.getProperty("user.home"), ".konan"))
}
