plugins {
	kotlin("jvm")
	application
}

group = "com.bitsycore"
version = "0.1-SNAPSHOT"

application {
	mainClass.set("com.bitsycore.ktc2.MainKt")
}

repositories {
	mavenCentral()
}

dependencies {
	// Frontend: the Kotlin K2 compiler. Pinned — its IR API is experimental.
	implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
	testImplementation(kotlin("test"))
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	jvmToolchain(21)
}

tasks.test {
	useJUnitPlatform()
	// The integration programs and the bundled runtime are inputs of the oracle test.
	inputs.dir("integration")
	testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
