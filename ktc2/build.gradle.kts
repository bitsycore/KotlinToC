plugins {
	kotlin("jvm")
	application
}

group = "com.bitsycore"
version = "0.1-SNAPSHOT"

application {
	mainClass.set("com.bitsycore.ktc2.MainKt")
}

tasks.named<JavaExec>("run") {
	dependsOn("unpackStdlibSources")
	systemProperty("ktc2.stdlibDir", layout.buildDirectory.dir("stdlib-src").get().asFile.absolutePath)
}

repositories {
	mavenCentral()
}

/** The Kotlin standard library sources (Apache-2), compiled together with user programs. */
val stdlibSources: Configuration by configurations.creating {
	isTransitive = false
}

dependencies {
	stdlibSources("org.jetbrains.kotlin:kotlin-stdlib:2.5.0-RC:sources")
	// Frontend: the Kotlin K2 compiler. Pinned — its IR API is experimental.
	implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.5.0-RC")
	testImplementation(kotlin("test"))
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	jvmToolchain(21)
}

val unpackStdlibSources by tasks.registering(Sync::class) {
	from({ stdlibSources.map { zipTree(it) } })
	into(layout.buildDirectory.dir("stdlib-src"))
}

tasks.test {
	useJUnitPlatform()
	dependsOn(unpackStdlibSources)
	systemProperty("ktc2.stdlibDir", layout.buildDirectory.dir("stdlib-src").get().asFile.absolutePath)
	// The integration programs and the bundled runtime are inputs of the oracle test.
	inputs.dir("integration")
	testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
