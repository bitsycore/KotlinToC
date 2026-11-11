plugins {
    kotlin("jvm") version "2.5.0-RC"
    application
}

group = "com.bitsycore"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("com.bitsycore.ktc.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.bitsycore.ktc.MainKt"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from({
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        }
    })

    // Re-run whenever a resource file changes (e.g. stdlib/*.kt)
    inputs.dir("src/main/resources")
}

repositories {
    mavenCentral()
}

val proguardClasspath: Configuration by configurations.creating

dependencies {
    testImplementation(kotlin("test"))
    proguardClasspath("com.guardsquare:proguard-base:7.9.1")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()

    // Propagate ktc.verifyCompile system property to test JVM.
    // Usage: ./gradlew test -Dktc.verifyCompile=true
    System.getProperty(" ")?.let {
        systemProperty("ktc.verifyCompile", it)
    }
}

tasks.register<JavaExec>("proguard") {
    dependsOn(tasks.jar)
    description = "Produces an optimized release jar via ProGuard"
    group = "build"

    val inputJar = tasks.jar.get().archiveFile.get().asFile
    val outputJar = layout.buildDirectory.file("libs/${project.name}-${version}-release.jar").get().asFile
    val proguardConf = layout.projectDirectory.file("proguard.pro")
    val launcher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    inputs.file(inputJar)
    inputs.file(proguardConf)
    outputs.file(outputJar)

    classpath = proguardClasspath
    mainClass.set("proguard.ProGuard")

    argumentProviders.add(CommandLineArgumentProvider {
        val javaHome = launcher.get().executablePath.asFile.parentFile.parentFile
        listOf(
            "-injars", inputJar.absolutePath,
            "-outjars", outputJar.absolutePath,
            "-libraryjars", "${javaHome}/jmods/java.base.jmod(!**.jar;!module-info.class)",
            "-libraryjars", "${javaHome}/jmods/java.logging.jmod(!**.jar;!module-info.class)",
            "@${proguardConf.asFile.absolutePath}"
        )
    })
}