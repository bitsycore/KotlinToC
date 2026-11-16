plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ktc2"

// The tests and the programs of the suite as modules for an IDE (ide/build.gradle.kts).
include("ide")
