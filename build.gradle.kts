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

application {
	mainClass.set("com.bitsycore.ktc2.MainKt")
	// A compilation is one short run: the quick JIT tier and a heap that does not have to grow make it a quarter faster.
	// With the quick tier alone the JVM reserves a small code cache, which the Kotlin compiler can fill: it is given room.
	applicationDefaultJvmArgs = listOf("-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=256m", "-XX:+UseParallelGC", "-Xms512m")
}

tasks.named<JavaExec>("run") {
	dependsOn("unpackStdlibSources")
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
 * A bundled module made of a library's own sources (all Apache-2): its sources jars, each unpacked
 * under `build/modules-src/<name>/<directory>` (the module's `files.txt` lists what it compiles as
 * `<directory>/<source set>/...`, or `<source set>/...` for the directory "."), and the library's jars
 * for the JVM, which the suite compiles the JVM side of a program on the module with.
 */
class LibraryModule(val name: String, val sources: Map<String, Provider<MinimalExternalModuleDependency>>, val jvm: List<Provider<MinimalExternalModuleDependency>>) {
	val sourcesConfiguration: Configuration = configurations.create("moduleSources-$name") { isTransitive = false }
	val jvmConfiguration: Configuration = configurations.create("moduleJvm-$name") { isTransitive = false }
}

val kLibraryModules = listOf(
	LibraryModule(
		"kotlinx-io",
		mapOf("core" to libs.kotlinx.io.core.asProvider(), "bytestring" to libs.kotlinx.io.bytestring.asProvider()),
		listOf(libs.kotlinx.io.core.jvm, libs.kotlinx.io.bytestring.jvm),
	),
	LibraryModule(
		"kotlinx-collections-immutable",
		mapOf("." to libs.kotlinx.collections.immutable.asProvider()),
		listOf(libs.kotlinx.collections.immutable.jvm),
	),
	LibraryModule("atomicfu", mapOf("." to libs.atomicfu.asProvider()), listOf(libs.atomicfu.jvm)),
	LibraryModule(
		"kotlinx-serialization",
		mapOf("core" to libs.kotlinx.serialization.core.asProvider(), "json" to libs.kotlinx.serialization.json.asProvider()),
		listOf(libs.kotlinx.serialization.core.jvm, libs.kotlinx.serialization.json.jvm),
	),
	// JSON on the streams of kotlinx-io; on the JVM, json-okio and okio too, which only its tests use.
	LibraryModule(
		"kotlinx-serialization-json-io",
		mapOf("." to libs.kotlinx.serialization.json.io.asProvider()),
		listOf(libs.kotlinx.serialization.json.io.jvm, libs.kotlinx.serialization.json.okio.jvm, libs.okio.jvm),
	),
	// The common jar has every source set but Windows' (windowsMain), which is in the jar of mingwX64.
	LibraryModule(
		"kotlinx-datetime",
		mapOf("." to libs.kotlinx.datetime.asProvider(), "mingw" to libs.kotlinx.datetime.mingwx64),
		listOf(libs.kotlinx.datetime.jvm),
	),
)

dependencies {
	stdlibSources(variantOf(libs.kotlin.stdlib) { classifier("sources") })
	for (vModule in kLibraryModules) {
		for (vSource in vModule.sources.values) add(vModule.sourcesConfiguration.name, variantOf(vSource) { classifier("sources") })
		for (vJar in vModule.jvm) add(vModule.jvmConfiguration.name, vJar)
	}
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
	into(layout.buildDirectory.dir("stdlib-src"))
}

val unpackModuleSources = tasks.register("unpackModuleSources", Sync::class) {
	description = "Unpack the sources of the libraries that are bundled modules (Apache-2) into the build directory."
	for (vModule in kLibraryModules) {
		for ((vDirectory, vSource) in vModule.sources) {
			// Each jar in a directory of its own: they all have a commonMain, a nativeMain...
			val vArtifact = vSource.get().module.name
			from({ vModule.sourcesConfiguration.filter { it.name.startsWith("$vArtifact-") && it.name.removePrefix("$vArtifact-").first().isDigit() }.map { vJar -> zipTree(vJar) } }) {
				into("${vModule.name}/$vDirectory")
			}
		}
	}
	into(layout.buildDirectory.dir("modules-src"))
}

/** The installed ktc2 carries the standard library sources it compiles with every program (those of files.txt). */
tasks.processResources {
	dependsOn(unpackStdlibSources, unpackModuleSources)
	// And the sources each bundled module of a library compiles (modules/<name>/files.txt).
	for (vModule in kLibraryModules) {
		val vList = file("src/main/resources/ktc2/modules/${vModule.name}/files.txt")
		// A line `windows: path` or `linux, macos: path` is a file of those systems only: all are carried.
		val vModuleListed = vList.readLines().map { it.trim().replace(Regex("^[a-z]+(\\s*,\\s*[a-z]+)*\\s*:\\s*"), "") }.filter { it.contains('/') && !it.startsWith("#") }
		inputs.file(vList)
		from(layout.buildDirectory.dir("modules-src/${vModule.name}")) {
			include(vModuleListed)
			into("ktc2/modules/${vModule.name}")
		}
	}
	val vListed = file("src/main/resources/ktc2/stdlib/files.txt").readLines().map { it.trim() }.filter { it.contains('/') && !it.startsWith("#") }
	inputs.file("src/main/resources/ktc2/stdlib/files.txt")
	from(layout.buildDirectory.dir("stdlib-src")) {
		include(vListed)
		into("ktc2/kotlin-stdlib")
	}
}

tasks.test {
	useJUnitPlatform()
	dependsOn(unpackStdlibSources)
	systemProperty("ktc2.stdlibDir", layout.buildDirectory.dir("stdlib-src").get().asFile.absolutePath)
	// A program that uses a bundled module made of a library's sources is compiled for the JVM with the library's JVM jars.
	for (vModule in kLibraryModules) systemProperty("ktc2.jvmModule.${vModule.name}", vModule.jvmConfiguration.files.joinToString(File.pathSeparator))
	// The integration programs and the bundled runtime are inputs of the oracle test.
	inputs.dir("integration")
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
	val kKinds = listOf("box" to "box test", "library" to "library test", "common" to "common test", "project" to "project", "integration" to "integration test")
	fun kindOf(inName: String): String = when {
		inName.startsWith("tests/box/") -> "box"
		inName.startsWith("tests/kotlinx-") || inName.startsWith("tests/atomicfu/") -> "library"
		inName.startsWith("tests/") -> "common"
		inName.startsWith("projects/") -> "project"
		else -> "integration"
	}
	val vKindColors = mapOf("box" to "35", "library" to "36", "common" to "34", "project" to "33", "integration" to "37")
	val vByKind = ConcurrentHashMap<String, IntArray>()
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
				val vText = "   ${vLabel}s".padEnd(18) + "${vCounts[0]} passed" + if (vCounts[1] > 0) ", ${vCounts[1]} failed" else ""
				println(if (vCounts[1] > 0) paint("31", vText) else paint(vKindColors.getValue(vKind), vText))
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
			// As an IDE shows them: each class of tests, then its test functions under it.
			fun functions(inAll: Boolean) {
				var vClass: String? = null
				for (vFunction in vFunctions) {
					val vPassed = vFunction.startsWith("ok ")
					if (vPassed && !inAll) continue
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
					println("$vProgress $vTag ${paint("32", "✔")} ${inTest.displayName}$vCounts $vTime")
					if (System.getenv("KTC2_TEST_FUNCTIONS") != "0") functions(true)
				}
				TestResult.ResultType.SKIPPED -> if (!vQuiet) println("$vProgress $vTag ${paint("33", "⏭")} ${inTest.displayName}")
				else -> {
					vFailed += inTest.displayName
					println("$vProgress $vTag ${paint("1;31", "✘ ${inTest.displayName}")}$vCounts $vTime")
					functions(false)
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
