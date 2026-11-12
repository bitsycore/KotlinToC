plugins {
	kotlin("jvm") version "2.5.0-RC"
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

dependencies {
	stdlibSources("org.jetbrains.kotlin:kotlin-stdlib:2.5.0-RC:sources")
	// Frontend: the Kotlin K2 compiler. Pinned - its IR API is experimental.
	implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.5.0-RC")
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

/** The installed ktc2 carries the standard library sources it compiles with every program (those of files.txt). */
tasks.processResources {
	dependsOn(unpackStdlibSources)
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
	// The integration programs and the bundled runtime are inputs of the oracle test.
	inputs.dir("integration")
	// The programs are shared between several processes (see IntegrationTest): KTC2_TEST_FORKS says how many,
	// at most 12; by default half the processors but no more than 8, past which they only get in each other's way. Each has a Java machine and a Kotlin compiler of its own.
	val vForks = (System.getenv("KTC2_TEST_FORKS")?.toIntOrNull() ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(8)).coerceIn(1, 12)
	maxParallelForks = vForks
	systemProperty("ktc2.testShards", vForks.toString())
	// Each process analyses the library once, for all its programs (KTC2_KEEP_LIBRARY=0: once per program).
	systemProperty("ktc2.keepLibrary", "true")
	maxHeapSize = "1g"
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
