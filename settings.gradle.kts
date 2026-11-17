plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ktc2"

// The compiler, with its test harness and what the installed tool carries (compiler/build.gradle.kts).
include("compiler")
// The `ktc2.` package on the JVM and on Kotlin/Native, for programs that use it elsewhere than ktc2.
include("compat")
// The tests and the programs of the suite as modules for an IDE (ide/build.gradle.kts).
include("ide")
