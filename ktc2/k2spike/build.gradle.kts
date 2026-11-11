plugins {
	kotlin("jvm")
	application
}

application {
	mainClass.set("com.bitsycore.ktc2.k2spike.SpikeKt")
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
	// The ktc2. package (annotations) must be on the classpath of the compiled sample.
	implementation(project(":ktc2"))
}

kotlin {
	jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
	dependsOn(tasks.jar)
	// The spike registers itself with the compiler as a plugin jar.
	systemProperty("ktc2.spike.pluginJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
