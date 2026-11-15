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
	// KTC2_TEST_VERBOSE=1 prints a line for each program as it passes, to follow a run in a terminal.
	val vEvents = if (System.getenv("KTC2_TEST_VERBOSE") != null) arrayOf("passed", "failed", "skipped") else arrayOf("failed")
	testLogging { events(*vEvents); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
