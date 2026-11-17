/*
The compat layer: the `ktc2.` package (`@Struct`, `@Value`, `@Weak`, `WeakRef`, `arena`, `createCleaner`,
`ktc2.concurrent`...) for the platforms other than ktc2, so that a program that uses it also compiles and
runs there, computing the same. src/main/kotlin is the JVM's (plain Kotlin over what the JVM has), which the
compiler puts on the class path of every compilation and the suite runs programs with; native/ is Kotlin/Native's,
compiled with each program by tools/compare.py. ktc2's own, in C, is in the platform layer (stdlib/Ktc2Lifetime.kt,
Ktc2Concurrent.kt), with the same signatures.
*/

plugins {
	alias(libs.plugins.kotlin.jvm)
}

repositories {
	mavenCentral()
}

base {
	archivesName.set("ktc2-compat")
}

kotlin {
	jvmToolchain(21)
}
