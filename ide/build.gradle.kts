/*
The tests and the programs of the suite as Kotlin modules for an IDE: each file is in a source set,
with what it needs on its class path, so that it is highlighted, resolved and runnable as plain
Kotlin on the JVM (the gutter's run button on `main`, or on a `@Test`). Nothing here is part of
the suite, which compiles each file by itself (IntegrationTest.kt). Gradle
compiles the programs of tests/integration/ for the JVM (`:ide:compileIntegrationObjectsKotlin`...).

- `stdlib`: ktc2's platform layer (modules/stdlib/), seen as Kotlin against the JVM's standard library:
  highlighted and navigable, most of it resolved. It is not compiled here (its compile task is
  off): it is `actual`s of the standard library's own `expect`s in package kotlin, which only
  ktc2's compilation, with the library's sources in their levels, puts together;
- `kotlinTests`: tests/stdlib (the tests of the Kotlin library, with their helpers of
  tests/stdlib/support) and tests/ktc2 (a package for each file), with kotlin.test. Read by the IDE, not compiled as one:
  the suite compiles each file by itself, without the `expect` lines its helpers answer;
- `box`: tests/box, with its helpers;
- `integration<Group><n>`: the programs of tests/integration/<group>/. Programs of one group declare
  the same names (`class Box`, `fun describe`), which one compilation cannot hold: a group is cut in
  as few parts as keep every name once in each (`partsOf`); a new program goes in the first part
  where its names are free. The groups that are C and not JVM (`interop`, which uses C libraries;
  `errors`, programs that must be refused) are left out, and so are the programs of several
  modules (`tests/integration/modules/`), each a project of its own for ktc2.
*/

plugins {
	alias(libs.plugins.kotlin.jvm)
}

repositories {
	mavenCentral()
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		// What the tests of the Kotlin library opt in to (its commonTestOptIns), as the suite compiles them.
		optIn.addAll("kotlin.ExperimentalUnsignedTypes", "kotlin.ExperimentalStdlibApi", "kotlin.io.encoding.ExperimentalEncodingApi", "kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
		// The language ktc2 compiles (kLanguageOptions in K2Frontend.kt), and the common code of a library's tests.
		freeCompilerArgs.addAll("-Xcompanion-blocks-and-extensions", "-Xcollection-literals", "-Xfull-value-classes", "-Xmulti-platform", "-nowarn")
	}
}

val kRoot: File = rootDir

/** The top-level declarations of a Kotlin file that another file of its package could clash with, with its package. */
fun namesOf(inFile: File): Set<String> {
	val vText = inFile.readText()
	val vPackage = Regex("(?m)^package\\s+([\\w.]+)").find(vText)?.groupValues?.get(1) ?: ""
	val vDeclaration = Regex("(?m)^((?:(?:private|internal|public|data|open|abstract|sealed|enum|inline|value|annotation|operator|infix|suspend|tailrec|const|lateinit|external)\\s+)*)(?:class|object|interface|fun|val|var|typealias)\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?(\\w+)")
	// `main` may be declared in every file; a private declaration is the file's own.
	return vDeclaration.findAll(vText).filter { "private" !in it.groupValues[1] && it.groupValues[2] != "main" }.map { "$vPackage.${it.groupValues[2]}" }.toSet()
}

/** The files of [inFiles] in as few parts as keep each name once in a part: each file in the first part it fits. */
fun partsOf(inFiles: List<File>): List<List<File>> {
	val vParts = ArrayList<Pair<MutableSet<String>, MutableList<File>>>()
	for (vFile in inFiles.sortedBy { it.path }) {
		val vNames = namesOf(vFile)
		val vPart = vParts.firstOrNull { (vTaken, _) -> vTaken.none { it in vNames } } ?: (HashSet<String>() to ArrayList<File>()).also { vParts += it }
		vPart.first += vNames
		vPart.second += vFile
	}
	return vParts.map { it.second }
}

val kGroups = File(kRoot, "tests/integration").listFiles { vFile -> vFile.isDirectory && vFile.name !in setOf("interop", "errors", "modules", "regressions") }!!.sortedBy { it.name }

sourceSets {
	create("stdlib") {
		kotlin.srcDir(File(kRoot, "modules/stdlib"))
	}
	create("kotlinTests") {
		kotlin.srcDir(File(kRoot, "tests/stdlib"))
		kotlin.srcDir(File(kRoot, "tests/ktc2"))
		// The helpers of the library's tests: the common part and the JVM's answers, not ktc2's (compiled for C only).
		kotlin.exclude("support/ktc2/**")
	}
	create("box") {
		kotlin.srcDir(File(kRoot, "tests/box"))
		kotlin.exclude("pending/**")
	}
	for (vGroup in kGroups) {
		val vFiles = vGroup.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
		for ((vIndex, vPart) in partsOf(vFiles).withIndex()) {
			val vName = "integration" + vGroup.name.replaceFirstChar { it.uppercase() } + (if (vIndex == 0) "" else "$vIndex")
			create(vName) {
				kotlin.srcDir(vGroup)
				val vIncluded = vPart.map { it.relativeTo(vGroup).invariantSeparatorsPath }.toSet()
				kotlin.include { vElement -> vElement.isDirectory || vElement.relativePath.pathString in vIncluded }
			}
		}
	}
}

// The platform layer is compiled by ktc2 only: the IDE reads it, Gradle does not compile it.
tasks.named("compileStdlibKotlin") { enabled = false }

dependencies {
	for (vSet in sourceSets.filter { it.name != "main" && it.name != "test" }) {
		add(vSet.implementationConfigurationName, libs.kotlin.stdlib)
		add(vSet.implementationConfigurationName, kotlin("test"))
		add(vSet.implementationConfigurationName, kotlin("test-junit5"))
		// The `ktc2.` package as the JVM has it (compat/), for the programs that use it.
		add(vSet.implementationConfigurationName, project(":compat"))
	}
}

// The tests of the Kotlin library see what it keeps internal, as its own tests do (and the suite, -Xfriend-paths).
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlinTestsKotlin") {
	friendPaths.from(configurations.named("kotlinTestsCompileClasspath").map { vClasspath -> vClasspath.filter { it.name.startsWith("kotlin-stdlib-") } })
}
